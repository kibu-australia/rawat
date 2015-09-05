(ns datomic-schema.core
  (:require [schema.core :as s]
            [datomic.api :as d]
            [clojure.walk :as walk]
            [clojure.data :refer [diff]]
            [clojure.core.match :refer [match]])
  (:import java.util.Date
           java.net.URI
           java.math.BigDecimal
           java.math.BigInteger
           java.util.UUID))

(s/defschema DatomicMetaAttrMap
  {(s/optional-key :db/doc) s/Str
   (s/optional-key :db/unique) (s/enum :db.unique/value :db.unique/identity)
   (s/optional-key :db/index) s/Bool
   (s/optional-key :db/fulltext) s/Bool
   (s/optional-key :db/isComponent) s/Bool
   (s/optional-key :db/noHistory) s/Bool})

(defprotocol IDatomicSchema
  (get-attrs [this]))

(defrecord DatomicMeta [attrs t]
  schema.core.Schema
  (spec [_] (s/spec t))
  (explain [_] (s/explain t))
  IDatomicSchema
  (get-attrs [_] (merge attrs (get-attrs t))))

(s/defn ^:always-validate datomic-meta :- DatomicMeta
  [attrs :- DatomicMetaAttrMap t :- s/Any]
  (DatomicMeta. attrs t))

#?(:clj
   (defn class->datomic-type [x]
     (condp = x
       String {:db/valueType :db.type/string}
       java.util.Date {:db/valueType :db.type/instant}
       java.lang.Boolean {:db/valueType :db.type/boolean}
       java.math.BigDecimal {:db/valueType :db.type/bigdec}
       java.lang.Double {:db/valueType :db.type/double}
       java.math.BigInteger {:db/valueType :db.type/bigint}
       java.lang.Number {:db/valueType :db.type/long}
       clojure.lang.Keyword {:db/valueType :db.type/keyword}
       java.util.UUID {:db/valueType :db.type/uuid}
       java.net.URI {:db/valueType :db.type/uri}
       ;; else, cannot handle
       (throw (Exception. (str "Don't know how to create schema for " (pr-str x)))))))

#?(:clj
   (extend-protocol IDatomicSchema
     java.lang.Class (get-attrs [this] (class->datomic-type this))
     java.lang.Boolean (get-attrs [_] )
     clojure.lang.PersistentArrayMap (get-attrs [_] {:db/valueType :db.type/ref})
     schema.core.Recursive (get-attrs [_] {:db/valueType :db.type/ref})
     schema.core.Predicate (get-attrs [_] {:db/valueType :db.type/keyword}) ;; s/Keyword returns s/pred
     clojure.lang.PersistentHashSet (get-attrs [this] (get-attrs (vec this)))
     clojure.lang.PersistentVector (get-attrs [this]
                                     (if-not (= (count this) 1)
                                       (throw (Exception. (str "Cannot create schema for " (pr-str this))))
                                       (let [attrs (get-attrs (first this))]
                                         (if (sequential? attrs) ;; enum
                                           (conj (vec attrs) {:db/cardinality :db.cardinality/many
                                                              :db/valueType :db.type/ref})
                                           {:db/cardinality :db.cardinality/many
                                            :db/valueType (:db/valueType attrs)}))))
     schema.core.One (get-attrs [this] (get-attrs (:schema this)))
     schema.core.Either (get-attrs [this] (map get-attrs (:schemas this)))
     schema.core.Maybe (get-attrs [this] (get-attrs (:schema this)))
     schema.core.EnumSchema (get-attrs [this]
                              ;; Returns each enumeration as ident
                              (let [[idents] (vals this)]
                                (map (fn [ident] {:db/ident ident}) idents)))
     Object (get-attrs [this] (throw (Exception. (str "Don't know how to create schema for " (class this))))))
   ;; TODO: write cljs equiv
   :cljs (extend-protocol IDatomicSchema default (get-attrs [_] {})))

(defn- get-key [k]
  (cond
    (keyword? k) k
    (instance? schema.core.OptionalKey k) (:k k)
    :else (throw (Exception. (str "Key " k " must be a keyword")))))

(defn enum? [x]
  (let [[k1 k2 & ks] (keys x)]
    (and (empty? ks)
         (some #{k1} [:db/ident :db/id])
         (some #{k2} [:db/ident :db/id]))))

(defn add-temp-id [x]
  (assoc x :db/id (d/tempid :db.part/db)))

(defn install-attribute [x]
  (if-not (enum? x) (assoc x :db.install/_attribute :db.part/db) x))

(defn build-schema [x]
  (if (map? x)
    (let [[k v] x
          default-attrs {:db/ident (get-key k)
                         :db/cardinality :db.cardinality/one}
          attrs (get-attrs v)]
      (if (sequential? attrs) ;; enum
        (if (= :db.cardinality/many (:db/cardinality (last attrs)))
          (conj (butlast attrs) (merge default-attrs (last attrs))) ;; has-many enum
          (conj attrs (assoc default-attrs :db/valueType :db.type/ref)))
        (if (sequential? v)
          (if (instance? clojure.lang.PersistentArrayMap (first v))
            (flatten [(merge default-attrs attrs) (into [] (mapcat build-schema) (first v))])
            [(merge default-attrs attrs)])
          [(merge default-attrs attrs)])))
    (get-attrs x)))

(def build-schema-tf (mapcat build-schema))

(defn validate-txes [txes]
  (loop [txes txes next-txes []]
    (if-let [tx (first txes)]
      (let [[tx-match] (filter (fn [x] (= (:db/ident tx) (:db/ident x))) next-txes)]
        (if tx-match
          (if (= tx tx-match)
            (recur (rest txes) next-txes)
            (throw (Exception. (str "Conflicting attributes for ident " (:db/ident tx)))))
          (recur (rest txes) (conj next-txes tx))))
      next-txes)))

(def build-tx-tf
  (comp build-schema-tf
        (map add-temp-id)
        (map install-attribute)))

(defn schemas->tx [schemas]
  (let [txes (into [] build-tx-tf schemas)]
    (validate-txes txes)))

;; Database conformity

(defn- flatten-pull [x]
  (into {} (map (fn [[k v]] [k (if (map? v) (:db/ident v) v)])) x))

(defn- get-attributes [db idents]
  (map
   flatten-pull
   (d/q '[:find [(pull ?e [:db/ident
                           :db/unique
                           :db/index
                           :db/fulltext
                           :db/isComponent
                           :db/noHistory
                           {:db/valueType [:db/ident]
                            :db/cardinality [:db/ident]
                            :db/doc [:db/ident]}])
                 ...]
          :in $ [?ident ...]
          :where [?e :db/ident ?ident]]
        db idents)))

(defn conforms?
  "Checks if database conforms to prismatc schemas"
  [conn schemas]
  (let [txes (map #(dissoc % :db/id :db.install/_attribute) (schemas->tx schemas))
        db-attrs (get-attributes (d/db conn) (map :db/ident txes))]
    (doseq [tx txes]
      (let [[db-attr] (filter #(= (:db/ident %) (:db/ident tx)) db-attrs)]
        (if (nil? db-attr)
          (throw (Exception. "Missing attribute from database " (:db/ident tx)))
          (when (not= db-attr tx)
            (throw (Exception. (str "Database attribute does not match schema. Got: "
                                    (pr-str db-attr) ", expected " (pr-str tx))))))))
    true))

;; Database diffing

;; See "Altering Schema Attributes" - http://docs.datomic.com/schema.html
(defn possible-alteration? [from to]
  (match [from to]
   [{:db/cardinality :db.cardinality/one} {:db/cardinality :db.cardinality/many}] true
   [{:db/cardinality :db.cardinality/many} {:db/cardinality :db.cardinality/one}] true
   [{:db/isComponent true} {:db/isComponent false}] true
   [{:db/isComponent true} {}] true
   [{:db/isComponent false} {:db/isComponent true}] true
   [{} {:db/isComponent true}] true
   [{:db/noHistory true} {:db/noHistory false}] true
   [{:db/noHistory true} {}] true
   [{:db/noHistory false} {:db/noHistory true}] true
   [{} {:db/noHistory true}] true
   [{:db/index true} {:db/index false}] true
   [{:db/index true} {}] true
   [{:db/index true} {:db/unique _}] true
   [{:db/index false} {:db/unique _}] true
   [{} {:db/unique _}] true
   [{:db/index true :db/unique _} {}] true
   [{:db/unique :db.unique/identity} {:db/unique :db.unique/value}] true
   [{:db/unique :db.unique/value} {:db/unique :db.unique/identity}] true
   :else false))

(def alterations
  [[:db/isComponent] [:db/cardinality] [:db/noHistory] [:db/index :db/unique] [:db/unique]])

(defn possible-alterations? [from to]
  (into {}
   (for [alteration alterations
         :let [from-value (select-keys from alteration)
               to-value (select-keys to alteration)]
         :when (or (not (empty? from-value)) (not (empty? to-value)))]
     [[from-value to-value] (possible-alteration? from-value to-value)])))

(defn db-diff
  "Returns the differnce between schemas and database

  Returns a map containing:
    * :diff - a transaction
    * :alterations - a map of any schema alterations each key being :db/ident "
  [conn schemas]
  (let [txes (schemas->tx schemas)
        db-attrs (get-attributes (d/db conn) (map :db/ident txes))]
    (loop [txes txes next-txes [] alterations {}]
      (if-let [tx (first txes)]
        (let [[db-attr] (filter #(= (:db/ident %) (:db/ident tx)) db-attrs)
              attr (dissoc tx :db/id :db.install/_attribute)]
          (if (nil? db-attr)
            (recur (rest txes) (conj next-txes tx) alterations)
            (if (not= db-attr attr)
              (let [[from to _] (diff db-attr attr)
                    next-alterations (assoc alterations (:db/ident tx) (possible-alterations? from to))]
                (recur (rest txes) next-txes next-alterations))
              (recur (rest txes) next-txes alterations))))
        {:diff next-txes :alterations alterations}))))
