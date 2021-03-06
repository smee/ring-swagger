(ns ring.swagger.swagger2
  (:require [clojure.string :as str]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [plumbing.core :refer [for-map fn->]]
            ring.swagger.json
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [ring.swagger.core :refer [with-named-sub-schemas collect-models peek-schema]]
            [ring.swagger.swagger2-schema :as schema]
            [instar.core :as instar]))

;;
;; Support Schemas
;;

(def Anything {s/Keyword s/Any})
(def Nothing {})

;;
;; Rendering options
;;

(def ^{:private true :dynamic true} *options* {})

;;
;; 2.0 Json Schema changes
;;

(defn ->json [& args]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (apply jsons/->json args)))

(defn ->properties [schema]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (let [properties (jsons/properties schema)]
      (if-not (empty? properties)
        properties))))

(defn ->additional-properties [schema]
    (binding [jsons/*swagger-spec-version* "2.0"]
      (jsons/additional-properties schema)))

;;
;; Schema transformations
;;

(defn extract-models [swagger]
  (let [route-meta (->> swagger
                        :paths
                        vals
                        (map vals)
                        flatten)
        body-models (->> route-meta
                         (map (comp :body :parameters)))
        response-models (->> route-meta
                             (map :responses)
                             (mapcat vals)
                             (keep :schema))]
    (concat body-models response-models)))

(defn transform [schema]
  (let [properties (->properties schema)
        additional-properties (->additional-properties schema)
        required (->> (required-keys schema)
                      (filter (partial contains? properties))
                      seq)]
    (remove-empty-keys
      {:properties properties
       :additionalProperties additional-properties
       :required required})))

(defn transform-models [schemas]
  (->> schemas
       collect-models
       (map (juxt (comp str key) (comp transform val)))
       (into {})))

;;
;; Paths, parameters, responses
;;

(defmulti ^:private extract-parameter first)

(defmethod extract-parameter :body [[_ model]]
  (if-let [schema (peek-schema model)]
    (let [schema-json (->json model)]
      (vector {:in :body
               :name (name (s/schema-name schema))
               :description (or (:description (->json schema)) "")
               :required true
               :schema (dissoc schema-json :description)}))))

(defmethod extract-parameter :default [[type model]]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key k)
                json-schema (->json v)]
          :when json-schema]
      (merge
        {:in type
         :name (name rk)
         :description ""
         :required (s/required-key? k)}
        json-schema))))

(defn- default-response-description
  "uses option :default-response-description-fn to generate
   a default response description for status code"
  [status]
  (if-let [generator (:default-response-description-fn *options*)]
    (generator status)
    ""))

(defn convert-parameters [parameters]
  (into [] (mapcat extract-parameter parameters)))

(defn convert-responses [responses]
  (let [responses (for-map [[k v] responses
                            :let [{:keys [schema headers]} v]]
                    k (-> v
                          (cond-> schema (update-in [:schema] ->json))
                          (cond-> headers (update-in [:headers] ->properties))
                          (update-in [:description] #(or % (default-response-description k)))
                          remove-empty-keys))]
    (if-not (empty? responses)
      responses
      {:default {:description ""}})))

(defn transform-operation
  "Returns a map with methods as keys and the Operation
   maps with parameters and responses transformed to comply
   with Swagger spec as values"
  [operation]
  (for-map [[k v] operation]
    k (-> v
          (update-in-or-remove-key [:parameters] convert-parameters empty?)
          (update-in [:responses] convert-responses))))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn extract-paths-and-definitions [swagger]
  (let [paths (->> swagger
                   :paths
                   (reduce-kv (fn [acc k v]
                                (assoc acc
                                  (swagger-path k)
                                  (transform-operation v))) {}))
        definitions (->> swagger
                         extract-models
                         transform-models)]
    [paths definitions]))

;;
;; Named top level schemas in body parameters and responses
;;

(defn ensure-body-and-response-schema-names
  "Takes a ring-swagger spec and returns a new version
   with a generated names for all anonymous nested schemas
   that come as body parameters or response models."
  [swagger]
  (-> swagger
      (instar/transform
        [:paths * * :parameters :body] #(with-named-sub-schemas % "Body"))
      (instar/transform
        [:paths * * :responses * :schema] #(with-named-sub-schemas % "Response"))))

;;
;; Schema
;;

(def swagger-defaults {:swagger "2.0"
                       :info {:title "Swagger API"
                              :version "0.0.1"}
                       :produces ["application/json"]
                       :consumes ["application/json"]})

;;
;; Public API
;;

(def Swagger schema/Swagger)

(def Options {(s/optional-key :ignore-missing-mappings?) s/Bool
              (s/optional-key :default-response-description-fn) (s/=> s/Str s/Int)})

(def option-defaults
  (s/validate Options {:ignore-missing-mappings? false
                       :default-response-description-fn (constantly "")}))

(s/defn swagger-json
  "Produces swagger-json output from ring-swagger spec.
   Optional second argument is a options map, supporting
   the following options with defaults:

   :ignore-missing-mappings? (false) - whether to silently ignore
   missing schema to json-schema mappings.

   :default-response-description-fn ((constantly \"\")) - a fn to generate
   default response descriptions from http status code"
  ([swagger :- (s/maybe Swagger)] (swagger-json swagger nil))
  ([swagger :- (s/maybe Swagger), options :- (s/maybe Options)]
    (let [options (merge option-defaults options)]
      (binding [jsons/*ignore-missing-mappings* (true? (:ignore-missing-mappings? options))
                *options* options]
        (let [[paths definitions] (-> swagger
                                      ensure-body-and-response-schema-names
                                      extract-paths-and-definitions)]
          (deep-merge
            swagger-defaults
            (-> swagger
                (assoc :paths paths)
                (assoc :definitions definitions))))))))
