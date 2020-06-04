(ns tech.ml
  (:require [tech.ml.registry :as registry]
            [tech.ml.protocols.system :as system-proto]
            [tech.ml.dataset :as ds]
            [tech.ml.dataset.column :as ds-col]
            [tech.ml.dataset.pipeline.column-filters :as cf]
            [tech.ml.gridsearch :as ml-gs]
            [tech.parallel :as parallel]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.casting :as casting]
            [tech.v2.datatype.functional :as dfn]
            [tech.ml.utils :as utils]
            [clojure.tools.logging :as log]
            [clojure.set :as c-set]
            [clojure.pprint :as pp])
  (:import [java.util UUID]))


(defn- normalize-column-seq
  [col-seq]
  (cond
    (sequential? col-seq)
    (vec col-seq)
    (set? col-seq)
    (vec col-seq)
    (or (keyword? col-seq)
        (string? col-seq))
    [col-seq]
    :else
    (throw (ex-info (format "Failed to normalize column sequence %s" col-seq)
                    {:column-seq col-seq}))))


(defn train
  "Train a model.  Returns a map of:
  {:model - direct result of system-proto/train
   :options - options used to train model
   :id - random UUID generated}"
  ([options feature-columns label-columns dataset]
   (when-not (->> (ds/columns dataset)
                  (map dtype/get-datatype)
                  (every? casting/numeric-type?))
     (throw (ex-info "Currently no systems can handle non-numeric data." {})))
   (let [feature-columns (normalize-column-seq feature-columns)
         label-columns (normalize-column-seq label-columns)
         dataset (-> (ds/->dataset dataset)
                     (ds/select (concat feature-columns label-columns) :all)
                     (ds/set-inference-target label-columns))]
     (when-not (> (count feature-columns) 0)
       (throw (ex-info "Must be at least one feature column to train"
                       {:feature-columns feature-columns})))
     (when-not (> (count label-columns) 0)
       (throw (ex-info "Must be at least one label column to train"
                       {:label-columns label-columns})))
     ;;We expect the users to serialize the options map so we want to save
     ;;enough relevant details of the dataset into the map that some portion of the
     ;;training process is accurately captured.
     (let [options (assoc options
                          :dataset-shape (dtype/shape dataset)
                          :feature-columns feature-columns
                          :label-columns label-columns
                          :label-map (ds/dataset-label-map dataset)
                          :column-map (->> (ds/columns dataset)
                                           (map (comp (juxt :name identity)
                                                      ds-col/metadata))
                                           (into {})))
           ml-system (registry/system (:model-type options))
           model (system-proto/train ml-system options (ds/->dataset dataset {}))]
       {:model model
        :options options
        :id (UUID/randomUUID)})))
  ([options dataset]
   (train options
          (or (:feature-columns options) (cf/feature? dataset))
          (or (:label-columns options) (cf/target? dataset))
          dataset)))


(defn thaw-model
  "As an optimization, you can thaw a model and hold onto it.  This removes the model
  byte array and adds a thawed model to the model-map.  For a relatively simple xgboost
  model (~200K), thawing alone took about 177ms the first time a model was thawed but
  only 2,3ms subsequently.  The intended use case is when you know you will infer
  repeatedly given the same model.  Unlike the result of train, theresult of thaw-model
  can can no longer be serialized.
  This can also be used to get into model-specific operations that cannot be done
  via the base ml protocols.
  Returns a new map with :model removed and :thawed-model added."
  [train-result]
  (-> train-result
      (update :thawed-model
              (fn [item]
                (or item (let [ml-system (registry/system
                                          (get-in train-result
                                                  [:options :model-type]))]
                           (system-proto/thaw-model ml-system
                                                    (:model train-result))))))
      (dissoc :model)))


(defn predict
  "Generate a sequence of predictions (inferences) from a training result.

  If classification, returns a sequence of probability distributions if possible.  If
  not, returns a map the selected option as the key and the probabily as the value.

  If regression, returns the sequence of predicated values."
  [{:keys [options model thawed-model] :as train-result} dataset]
  (let [feature-columns (:feature-columns options)
        _ (when-not (seq feature-columns)
            (throw (ex-info "Feature columns are missing" train-result)))
        ;;Order columns identical to training and remove anything else.
        ;;The select implicitly checks that the columns exist.

        dataset (-> (ds/select (ds/->dataset dataset)
                               feature-columns :all)
                    (ds/update-columns
                     feature-columns
                     #(ds-col/set-metadata % (assoc (ds-col/metadata %)
                                                    :column-type
                                                    :feature))))]
    ;;If this isn't true you are in trouble
    (assert (= (set feature-columns)
               (set (cf/feature? dataset))))
    (let [ml-system (registry/system (:model-type options))
          thawed-model (-> (thaw-model train-result)
                           :thawed-model)]
      (system-proto/predict ml-system options thawed-model dataset))))


(defn explain-model
  ([{:keys [options model] :as train-result} explain-options]
   (let [ml-system (registry/system (:model-type options))]
     (system-proto/explain-model ml-system model (merge options explain-options))))
  ([train-result]
   (explain-model train-result {})))


(defn dataset-seq->dataset-model-seq
  "Given a sequence of {:train-ds ...} datasets, produce a sequence of:
  {:model ...}
  train-ds is removed to keep memory usage as low as possible.
  See ds/dataset->k-fold-datasets"
  [options dataset-seq]
  (->> dataset-seq
       (map (fn [{:keys [train-ds] :as dataset-entry}]
              (let [{model :retval
                     train-time :milliseconds}
                    (utils/time-section (train options train-ds))]
                (-> (merge (assoc model :train-time train-time)
                           (dissoc dataset-entry :train-ds))))))))


(defn average-prediction-error
  "Average prediction error across models generated with these datasets
  Page 242, https://web.stanford.edu/~hastie/ElemStatLearn/.
  Result is the best train result annoted with the average loss of the group
  and total train and predict times."
  [options loss-fn dataset-seq]
  (when-not (seq dataset-seq)
    (throw (ex-info "Empty dataset sequence" {:dataset-seq dataset-seq})))
  (let [train-predict-data
        (->> (dataset-seq->dataset-model-seq options dataset-seq)
             (map (fn [{:keys [test-ds options model] :as train-result}]
                    (let [{predictions :retval
                           predict-time :milliseconds}
                          (utils/time-section (predict train-result test-ds))
                          labels (ds/labels test-ds)]
                      (merge (dissoc train-result :test-ds)
                             {:predict-time predict-time
                              :loss (loss-fn predictions labels)})))))
        ds-count (count dataset-seq)
        ave-fn #(* (/ 1.0 ds-count) %)
        total-seq #(apply + 0 %)
        total-loss (total-seq (map :loss train-predict-data))
        total-predict (total-seq (map :predict-time train-predict-data))
        total-train (total-seq (map :train-time train-predict-data))]
    (merge (->> train-predict-data
                (sort-by :loss)
                first)
           {:average-loss (ave-fn total-loss)
            :total-train-time total-train
            :total-predict-time total-predict})))


(defn auto-gridsearch-options
  [options]
  (let [ml-system (registry/system (:model-type options))]
    (merge (system-proto/gridsearch-options ml-system options)
           options)))


;;The gridsearch error reporter is called when there is an error during gridsearch.
;;It is called like so:
;;(*gridsearch-error-reporter options-map error)
(def ^:dynamic *gridsearch-error-reporter* #(log/warn %2 (with-out-str
                                                           (clojure.pprint/pprint
                                                            %1))))


(defn gridsearch
  "Gridsearch these system/option pairs by this dataset, averaging the errors
  across k-folds and taking the lowest top-n options.
We are breaking out of 'simple' and into 'easy' here, this is pretty
opinionated.  The point is to make 80% of the cases work great on the
first try."
  [{:keys [parallelism top-n gridsearch-depth k-fold]
    :or {parallelism (.availableProcessors
                      (Runtime/getRuntime))
         top-n 5
         gridsearch-depth 50
         k-fold 5}
    :as options}
   loss-fn dataset]
  (when-not (->> (ds/columns dataset)
                 (map dtype/get-datatype)
                 (every? casting/numeric-type?))
    (throw (ex-info "Currently no systems can handle non-numeric data."
                    {:non-numeric-columns
                     (->> (ds/columns dataset)
                          (map ds-col/metadata)
                          (filter #(= :string (:datatype %))))})))
  ;;Should do row-major conversion here and make it work later.  We specifically
  ;;know the feature and labels can't change.
  (log/infof "Gridsearching: %s"
             (with-out-str
               (pp/pprint {:top-n top-n
                           :gridsearch-depth gridsearch-depth
                           :k-fold k-fold})))
  (let [dataset-seq (if (and k-fold (> (int k-fold) 1))
                      (vec (ds/->k-fold-datasets dataset k-fold options))
                      [(ds/->train-test-split dataset options)])]
    (->> (ml-gs/gridsearch options)
         (take gridsearch-depth)
         (parallel/queued-pmap
          parallelism
          (fn [options-map]
            (try
              (let [retval
                    (average-prediction-error options-map
                                              loss-fn
                                              dataset-seq)]
                (if (dfn/valid? (:average-loss retval))
                  retval
                  (do
                    (log/warnf "Model produced nan or inf loss: %s"
                               (with-out-str
                                 (pp/pprint retval)))
                    nil)))
              (catch Throwable e
                (when *gridsearch-error-reporter*
                  (*gridsearch-error-reporter* options-map e))
                nil))))
         (remove nil?)
         ;;Partition to keep sorting time down a bit.
         (partition-all top-n)
         (reduce (fn [best-items next-group]
                   (->> (concat best-items next-group)
                        (sort-by :average-loss)
                        (take top-n)))
                 []))))
