(ns tech.libs.smile.regression
  (:require [tech.ml.model :as model]
            [tech.ml.protocols.system :as ml-proto]
            [tech.ml.dataset :as ds]
            [tech.ml.registry :as registry]
            ;;Kernels have to be loaded
            ;;[tech.libs.smile.kernels]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.functional :as dfn]
            [tech.ml.gridsearch :as ml-gs]
            [clojure.reflect :refer [reflect]])
  (:import [smile.regression
            Regression
            ;; GradientTreeBoost$Loss
            ;; NeuralNetwork$ActivationFunction
            RidgeRegression
            ElasticNet
            LASSO
            ;; OnlineRegression
            ]
           [smile.data.formula Formula]
           [java.lang.reflect Field]
           [java.util Properties List]))


(def package-name "smile.regression")


(def regression-class-names
  #{
    "ElasticNet"
    "GaussianProcessRegression"
    "GradientTreeBoost"
    "LASSO"
    "NeuralNetwork"
    "OLS"
    "RandomForest"
    "RBFNetwork"
    "RegressionTree"
    "RidgeRegression"
    "RLS"
    "SVR"
    })


(def regression-metadata
  {:elastic-net {:options [{:name :lambda1
                            :type :float64
                            :default 0.1
                            :range :>0}
                           {:name :lambda2
                            :type :float64
                            :default 0.1
                            :range :>0}
                           {:name :tolerance
                            :type :float64
                            :default 1e-4
                            :range :>0}
                           {:name :max-iterations
                            :type :int32
                            :default (int 1000)
                            :range :>0}]
                 :property-name-stem "smile.elastic.net"
                 :class-name "ElasticNet"
                 :gridsearch-options {:lambda1 (ml-gs/exp [1e-2 1e2])
                                      :lambda2 (ml-gs/exp [1e-4 1e2])
                                      :tolerance (ml-gs/exp [1e-6 1e-2])
                                      :max-iterations (ml-gs/exp [1e4 1e7])}
                 :constructor #(ElasticNet/fit %1 %2 %3)}

   ;; :gaussian-process {:options [{:name :kernel
   ;;                                :type :mercer-kernel
   ;;                               :default {:kernel-type :gaussian}}
   ;;                              {:name :lambda
   ;;                               :type :float64
   ;;                               :range :>0
   ;;                               :default 2}]
   ;;                    :datatypes #{:float64-array :sparse :int32-array}
   ;;                    :class-name "GaussianProcessRegression"
   ;;                    :attributes #{:object-data}}

   ;; :gaussian-process-regressors
   ;; {:options [{:name :inducing-samples
   ;;             :type :input-array}
   ;;            {:name :kernel
   ;;             :type :mercer-kernel
   ;;             :default {:kernel-type :gaussian}}
   ;;            {:name :lambda
   ;;             :type :float64
   ;;             :range :>0
   ;;             :default 2}]
   ;;  :datatypes #{:float64-array :sparse :int32-array}
   ;;  :class-name "GaussianProcessRegression"
   ;;  :attributes #{:object-data}}

   ;; :gaussian-process-nystrom
   ;; {:options [{:name :inducing-samples
   ;;             :type :input-array}
   ;;            {:name :kernel
   ;;             :type :mercer-kernel
   ;;             :default {:kernel-type :gaussian}}
   ;;            {:name :lambda
   ;;             :type :float64
   ;;             :range :>0
   ;;             :default 2}
   ;;            {:name :nystrom-marker
   ;;             :type :boolean
   ;;             :default true}]
   ;;  :datatypes #{:float64-array :sparse :int32-array}
   ;;  :class-name "GaussianProcessRegression"
   ;;  :attributes #{:object-data}}

   ;; :gradient-tree-boost
   ;; {:options [{:name :loss
   ;;             :type :enumeration
   ;;             :class-type GradientTreeBoost$Loss
   ;;             :lookup-table
   ;;             {:least-squares GradientTreeBoost$Loss/LeastSquares
   ;;              :least-absolute-deviation GradientTreeBoost$Loss/LeastAbsoluteDeviation
   ;;              :huber GradientTreeBoost$Loss/Huber}
   ;;             :default :least-squares}
   ;;            {:name :n-trees
   ;;             :type :int32
   ;;             :default 500
   ;;             :range :>0}
   ;;            {:name :max-nodes
   ;;             :type :int32
   ;;             :default 6
   ;;             :range :>0}
   ;;            {:name :shrinkage
   ;;             :type :float64
   ;;             :default 0.005
   ;;             :range :>0}
   ;;            {:name :sampling-fraction
   ;;             :type :float64
   ;;             :default 0.7
   ;;             :range [0.0 1.0]}]
   ;;  :datatypes #{:float64-array}
   ;;  :class-name "GradientTreeBoost"}

   :lasso
   {:options [{:name :lambda
               :type :float64
               :default 1.0
               :range :>0}
              {:name :tolerance
               :type :float64
               :default 1e-4
               :range :>0}
              {:name :max-iterations
               :type :int32
               :default 1000
               :range :>0}]
    :gridsearch-options {:lambda (ml-gs/exp [1e-4 1e1])
                         :tolerance (ml-gs/exp [1e-6 1e-2])
                         :max-iterations (ml-gs/linear-long [1e4 1e7])}
    :class-name "LASSO"
    :datatypes #{:float64-array}}


   :ridge
   {:options [{:name :lambda
               :type :float64
               :default 1.0
               :range :>0}]
    :gridsearch-options {:lambda (ml-gs/exp [1e-4 1e4])}
    :class-name "RidgeRegression"
    :datatypes #{:float64-array}}

   ;; :neural-network
   ;; {:options [{:name :activation-function
   ;;             :type :enumeration
   ;;             :class-type NeuralNetwork$ActivationFunction
   ;;             :lookup-table
   ;;             {:logistic-sigmoid NeuralNetwork$ActivationFunction/LOGISTIC_SIGMOID
   ;;              :tanh NeuralNetwork$ActivationFunction/TANH}
   ;;             :default :logistic-sigmoid}
   ;;            {:name :momentum
   ;;             :type :float64
   ;;             :default 1e-4
   ;;             :range :>0}
   ;;            {:name :weight-decay
   ;;             :type :float64
   ;;             :default 0.9}
   ;;            {:name :layer-sizes
   ;;             :type :int32-array
   ;;             :default (int-array [100])}
   ;;            {:name :learning-rate
   ;;             :default 0.1
   ;;             :type :float64
   ;;             :setter "setLearningRate"}]
   ;;  :class-name "NeuralNetwork"
   ;;  :datatypes #{:float64-array}
   ;;  :attributes #{:online}}

   ;; :ordinary-least-squares
   ;; {:options [{:name :svd?
   ;;             :type :boolean
   ;;             :default false}]
   ;;  :gridsearch-options {:svd? (ml-gs/nominative [true false])}
   ;;  :class-name "OLS"
   ;;  :datatypes #{:float64-array}}

   ;; :recursive-least-squares
   ;; {:options [{:name :forgetting-factor
   ;;             :default 1.0
   ;;             :type :float64}]
   ;;  :gridsearch-options {:forgetting-factor (ml-gs/exp [1e-2 1])}
   ;;  :datatypes #{:float64-array}
   ;;  :class-name "RLS"}

   ;; :support-vector
   ;; {:options [{:name :kernel
   ;;             :type :mercer-kernel
   ;;             :default {:kernel-type :gaussian}}
   ;;            {:name :loss-function-error-threshold
   ;;             :default 0.1
   ;;             :type :float64
   ;;             :altname "eps"}
   ;;            {:name :soft-margin-penalty
   ;;             :default 1.0
   ;;             :type :float64
   ;;             :altname "C"}
   ;;            {:name :tolerance
   ;;             :default 1e-3
   ;;             :type :float64
   ;;             :altname "tol"}]
   ;;  :gridsearch-options {:kernel {:kernel-type (ml-gs/nominative [:gaussian :linear])}
   ;;                       :loss-function-error-threshold (ml-gs/exp [1e-4 1e-1])
   ;;                       :soft-margin-penalty (ml-gs/exp [1e-4 1e2])
   ;;                       :tolerance (ml-gs/linear [1e-9 1e-1])}
   ;;  :class-name "SVR"
   ;;  :datatypes #{:float64-array :sparse :int32-array}
   ;;  :attributes #{:object-data}}
   ;; :random-forest
   ;; {:options [{:name :ntrees
   ;;             :type :int32
   ;;             :default 500}
   ;;            {:name :maxNodes
   ;;             :type :int32
   ;;             :default 100}
   ;;            {:name :node-size
   ;;             :type :int32
   ;;             :default 5}
   ;;            {:name :num-decision-variables
   ;;             :type :int32
   ;;             :default #(long (Math/ceil
   ;;                              (double
   ;;                               (/ (utils/options->feature-ecount %)
   ;;                                  3))))}
   ;;            {:name :sampling-rate
   ;;             :type :float64
   ;;             :default 1.0}]
   ;;  :class-name "RandomForest"
   ;;  :attributes #{:attributes}}
   })


(def marker-interfaces
  {:regression "Regression"
   :online-regression "OnlineRegression"})


(defmulti model-type->regression-model
  (fn [model-type]
    model-type))


(defmethod model-type->regression-model :default
  [model-type]
  (if-let [retval (get regression-metadata model-type)]
    retval
    (throw (ex-info "Failed to get regression type"
                    {:model-type model-type}))))


(defmethod model-type->regression-model :regression
  [model-type]
  (get regression-metadata :elastic-net))


(defn reflect-regression
  [cls-name]
  (reflect (Class/forName (str package-name "." cls-name))))


;; (defn- train-block
;;   "Train by downloading all the data into a fixed matrix."
;;   [options entry-metadata row-major-dataset]
;;   (let [value-seq (->> row-major-dataset
;;                        (map :features))
;;         [x-data x-datatype] (if (contains? (:attributes entry-metadata)
;;                                            :object-data)
;;                               [(object-array value-seq) :object-array]
;;                               [(into-array value-seq) :float64-array-array])

;;         n-entries (first (dtype/shape x-data))
;;         ^doubles y-data (first (dtype/copy-raw->item!
;;                                 (map :label row-major-dataset)
;;                                 (dtype/make-array-of-type :float64 n-entries)
;;                                 0))
;;         data-constructor-arguments [{:type x-datatype
;;                                      :default x-data
;;                                      :name :training-data}
;;                                     {:type :float64-array
;;                                      :default y-data
;;                                      :name :labels}]]
;;     (-> (utils/prepend-data-constructor-arguments entry-metadata options
;;                                                   data-constructor-arguments)
;;         (utils/construct package-name options))))


(defn- ->str
  [item]
  (if (or (keyword? item) (symbol? item))
    (name item)
    (str item)))


(defn options->properties
  ^Properties [metadata options]
  (let [pname-stem (:property-name-stem metadata)]
    (->> (:options metadata)
         (reduce (fn [^Properties props {:keys [name default]}]
                   (.put props (format "%s.%s"
                                       pname-stem
                                       (.replace ^String (clojure.core/name name)
                                                 "-" "."))
                         (str (dtype/cast (or (get options name) default)
                                          (dtype/get-datatype default))))
                   props)
                 (Properties.)))))


(defrecord SmileRegression []
  ml-proto/PMLSystem
  (system-name [_] :smile.regression)
  (gridsearch-options [system options]
    (let [entry-metadata (model-type->regression-model
                          (model/options->model-type options))]
      (if-let [retval (:gridsearch-options entry-metadata)]
        retval
        (throw (ex-info "Model type does not support auto gridsearch yet"
                        {:entry-metadata entry-metadata})))))
  (train [system options dataset]
    (let [entry-metadata (model-type->regression-model
                          (model/options->model-type options))
          target-colnames (->> (map meta dataset)
                               (filter #(= :inference (:column-type %))))
          _ (when-not (= 1 (count target-colnames))
              (throw (Exception. "Dataset has none or too many target columns.")))
          formula (Formula. (->str (:name (first target-colnames))))
          data (ds/dataset->smile-dataframe dataset)
          properties (options->properties entry-metadata options)
          ctor (:constructor entry-metadata)
          model (ctor formula data properties)]
      (model/model->byte-array model)))
  (thaw-model [system model]
    (model/byte-array->model model))
  (predict [system options thawed-model dataset]
    (let [^List row-major-dataset (ds/->row-major dataset options)
          ^Regression trained-model thawed-model]
      (->> (dtype/make-reader
            :float64
            (ds/row-count dataset)
            (.predict trained-model
                      ^doubles (:features (.get row-major-dataset idx))))
           (dtype/make-container :java-array :float64)))))


(defn get-field
  [obj fname]
  (let [field (doto (.getDeclaredField (type obj) fname)
                (.setAccessible true))]
    (.get field obj)))

(defn explain-linear-model
  [model {:keys [feature-columns]}]
  (let [weights (get-field model "w")
        bias (get-field model "b")]
    {:bias bias
     :coefficients (->> (map vector
                             feature-columns
                             (dtype/->reader weights))
                        (sort-by (comp second) >))}))


(extend-protocol ml-proto/PInternalMLModelExplain
  LASSO
  (model-explain-model [model options]
    (explain-linear-model model options))
  RidgeRegression
  (model-explain-model [model options]
    (explain-linear-model model options))
  ElasticNet
  (model-explain-model [model options]
    (explain-linear-model model options)))


(def system (constantly (->SmileRegression)))


(registry/register-system (system))
