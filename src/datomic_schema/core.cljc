(ns datomic-schema.core
  (:require [schema.core :as s]
            [datomic.api :as d]
            [clojure.walk :as walk])
  (:import java.util.Date
           java.net.URI
           java.math.BigDecimal
           java.math.BigInteger
           java.util.UUID
           schema.core.Schema))

(defprotocol IDatomicSchema
  (get-attrs [this]))

(defrecord DatomicMeta [attrs t]
  schema.core.Schema
  (spec [_] (s/spec t))
  (explain [_] (s/explain t))
  IDatomicSchema
  (get-attrs [_] (merge attrs (get-attrs t))))

(defn datomic-meta [attrs t]
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

(def add-temp-id
  (map (fn [x] (assoc x :db/id #db/id [:db.part/db]))))

(def build-schema
  (mapcat (fn [[k v]]
            (let [default-attrs {:db/ident (get-key k)
                                 :db/cardinality :db.cardinality/one}
                  attrs (get-attrs v)]
              (if (sequential? attrs) ;; enum
                (if (= :db.cardinality/many (:db/cardinality (last attrs)))
                  (conj (butlast attrs) (merge default-attrs (last attrs))) ;; has-many enum
                  (conj attrs (assoc default-attrs :db/valueType :db.type/ref)))
                (if (sequential? v)
                  (if (instance? clojure.lang.PersistentArrayMap (first v))
                    (flatten [(merge default-attrs attrs) (into [] build-schema (first v))])
                    [(merge default-attrs attrs)])
                  [(merge default-attrs attrs)]))))))

(defn validate-txes [txes]
  (loop [txes txes next-txes []]
    (if-let [tx (first txes)]
      (let [[tx-match] (filter (fn [x] = (:db/ident tx) (:db/ident x)) next-txes)]
        (if tx-match
          (if (= tx tx-match)
            (recur (rest txes) next-txes)
            (throw (Exception. (str "Conflicting attributes for ident " (:db/ident tx)))))
          (recur (rest txes) (conj next-txes tx))))
      next-txes)))

(defn schemas->tx [& schemas]
  (let [txes (into [] (comp build-schema add-temp-id) (apply concat schemas))]
    (validate-txes txes)))

(defn- get-attributes [db idents]
  (d/q '[:find (pull ?e [:db/valueType
                         :db/cardinality
                         :db/doc
                         :db/unique
                         :db/index
                         :db/fulltext
                         :db/isComponent
                         :db/noHistory]) .
         :in $ [?ident ...]
         :where [?e :db/ident ?ident]]
       db idents))

(defn conforms?
  "Checks if database conforms to prismatc schemas"
  [conn & schemas]
  (let [txes (map #(dissoc % :db/id) (apply schemas->tx schemas))
        db-attrs (get-attributes (d/db conn) (map :db/ident txes))]
    (doseq [tx txes]
      (let [[db-attr] (filter #(= (:db/ident %) (:db/ident tx)) db-attrs)]
        (if (nil? db-attr)
          (throw (Exception. "Missing attribute from database " (:db/ident tx)))
          (when (not= db-attr tx)
            (throw (Exception. (str "Database attribute does not match schema. Got: "
                                    (pr-str db-attr) ", expected " (pr-str tx))))))))
    true))
