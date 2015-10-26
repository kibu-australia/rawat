(ns rawat.core
  (:require [schema.core :as s :include-macros true]
            #?@(:clj [[datomic.api :as d]
                      [clojure.data :refer [diff]]
                      [clojure.core.match :refer [match]]]
                :cljs [[rawat.datomic :as d]
                       [cljs.core.match :refer-macros [match] :include-macros true]]))
  #?(:clj (:import java.util.Date
                   java.net.URI
                   java.math.BigDecimal
                   java.math.BigInteger
                   java.util.UUID)
     :cljs (:import goog.math.Long
                    goog.Uri)))

(defn ns->schemas [ns]
  (for [var (vals (ns-interns ns))
        :let [schema (var-get var)]
        :when (:name (meta schema))]
    schema))

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
  (get-attrs [this]
    (let [t-attrs (get-attrs t)]
      (if (sequential? t-attrs)
        (if (:db/cardinality (last t-attrs))
          (conj (vec (butlast t-attrs)) (merge attrs (last t-attrs))) ;; many
          (conj (vec t-attrs) (assoc attrs :db/cardinality :db.cardinality/one :db/valueType :db.type/ref))) ;; eq
        (merge attrs t-attrs)))))

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
       datomic.db.DbId {}
       ;; else, cannot handle
       (throw (Exception. (str "Don't know how to create schema for " (pr-str x))))))

   :cljs
   (defn class->datomic-type [x]
     (condp = x
       string {:db/valueType :db.type/string}
       date {:db/valueType :db.type/instant}
       boolean {:db/valueType :db.type/boolean}
       double {:db/valueType :db.type/double}
       goog.math.Long {:db/valueType :db.type/long}
       number {:db/valueType :db.type/long}
       UUID {:db/valueType :db.type/uuid}
       cljs.core.Keyword {:db/valueType :db.type/keyword}
       goog.Uri {:db/valueType :db.type/uri}
       rawat.datomic.DbId {}
       ;; else, cannot handle
       (throw (js/Error. (str "Don't know how to create schema for " (pr-str x)))))))

(defn- get-attrs-vec [this]
  (if-not (= (count this) 1)
    (throw (Exception. (str "Cannot create schema for " (pr-str this))))
    (let [attrs (get-attrs (first this))]
      (if (sequential? attrs) ;; enum
        (conj (vec attrs) {:db/cardinality :db.cardinality/many
                           :db/valueType :db.type/ref})
        {:db/cardinality :db.cardinality/many
         :db/valueType (:db/valueType attrs)}))))

(defonce ^:private attr-ref {:db/valueType :db.type/ref})

(extend-protocol IDatomicSchema
  schema.core.Recursive (get-attrs [_] {:db/valueType :db.type/ref})
  schema.core.Predicate (get-attrs [_] {:db/valueType :db.type/keyword}) ;; s/Keyword returns s/pred
  schema.core.EqSchema (get-attrs [this]
                         (assert (keyword? (:v this)) (str "Enumerated value must be keyword " (pr-str this)))
                         [{:db/ident (:v this)}])
  schema.core.One (get-attrs [this] (get-attrs (:schema this)))
  schema.core.Either (get-attrs [this] (map get-attrs (:schemas this)))
  schema.core.Maybe (get-attrs [this] (get-attrs (:schema this)))
  schema.core.EnumSchema (get-attrs [this]
                           ;; Returns each enumeration as datom
                           (let [[idents] (vals this)]
                             (map (fn [ident]
                                    (assert (keyword? ident) (str "Enumerated value must be keyword " (pr-str ident)))
                                    {:db/ident ident})
                                  idents)))

  #?@(:clj [java.lang.Class (get-attrs [this] (class->datomic-type this))
            Object (get-attrs [this] (throw (Exception. (str "Don't know how to create schema for " (class this) " - " (pr-str this)))))]

      :cljs [default (get-attrs [this]
                      (or (class->datomic-type this)
                          (throw (js/Error. (str "Don't know how to create schema for " (class this) " - " (pr-str this))))))])

  #?(:clj clojure.lang.PersistentHashMap :cljs cljs.core.PersistentHashMap) (get-attrs [_] attr-ref)
  #?(:clj clojure.lang.PersistentArrayMap :cljs cljs.core.PersistentArrayMap) (get-attrs [_] attr-ref)
  #?(:clj clojure.lang.Var :cljs cljs.core.Var) (get-attrs [_] attr-ref)
  #?(:clj clojure.lang.PersistentHashSet :cljs cljs.core.PersistentHashSet) (get-attrs [this] (get-attrs (vec this)))
  #?(:clj clojure.lang.PersistentVector :cljs cljs.core.PersistentVector) (get-attrs [this] (get-attrs-vec this)))

(defn- get-key [k]
  (cond
    (keyword? k) k
    (instance? DatomicMeta k) (get-key (:k k))
    (instance? schema.core.OptionalKey k) (:k k)
    :else (throw (#?(:clj Exception. :cljs js/Error.) (str "Key " k " must be keyword")))))

(defn- enum? [x]
  (let [[k1 k2 & ks] (keys x)]
    (or (and (empty? ks)
             (some #{k1} [:db/ident :db/id])
             (some #{k2} [:db/ident :db/id]))
        (and (empty? ks)
             (nil? k2)
             (some #{k1} [:db/ident])))))

(defn- add-temp-id [x]
  (if (enum? x)
    (assoc x :db/id (d/tempid :db.part/user))
    (assoc x :db/id (d/tempid :db.part/db))))

(defn- install-attribute [x]
  (if-not (enum? x) (assoc x :db.install/_attribute :db.part/db) x))

(defn build-schema [x]
  (cond
    (instance? schema.core.EnumSchema x)
    (get-attrs x)

    (map? x)
    (into [] (mapcat build-schema) x)

    (instance? #?(:clj clojure.lang.MapEntry :cljs cljs.core.MapEntry) x)
    (let [[k v] x
          ident (get-key k)]
      (if (= :db/id ident)
        []
        (let [default-attrs {:db/ident (get-key k)
                             :db/cardinality :db.cardinality/one}
              default-attrs (if (instance? DatomicMeta k) (merge (:attrs k) default-attrs) default-attrs)
              attrs (get-attrs v)]
          (if (sequential? attrs) ;; enum
            (if (= :db.cardinality/many (:db/cardinality (last attrs)))
              (conj (butlast attrs) (merge default-attrs (last attrs))) ;; has-many enum
              (if (= :db.cardinality/one (:db/cardinality (last attrs)))
                (conj (butlast attrs) (merge default-attrs (last attrs)))
                (conj attrs (assoc default-attrs :db/valueType :db.type/ref))))
            (if (sequential? v)
              (if (map? (first v))
                (flatten [(merge default-attrs attrs) (build-schema (first v))])
                [(merge default-attrs attrs)])
              [(merge default-attrs attrs)])))))

    :else (throw (#?(:clj Exception. :cljs js/Error.) (str "Don't know how to build datom for " (class x))))))

(def build-schema-tf (mapcat build-schema))

(defn validate-txes [txes]
  (loop [txes txes next-txes []]
    (if-let [tx (first txes)]
      (let [[tx-match] (filter (fn [x] (= (:db/ident tx) (:db/ident x))) next-txes)]
        (if tx-match
          (if (= (dissoc tx :db/id) (dissoc tx-match :db/id))
            (recur (rest txes) next-txes)
            (throw (#?(:clj Exception. :cljs js/Error.) (str "Conflicting attributes for datom " (:db/ident tx)))))
          (recur (rest txes) (conj next-txes tx))))
      next-txes)))

(def build-tx-tf
  (comp build-schema-tf
        (map add-temp-id)
        (map install-attribute)
        (remove #(= :db/id (:db/ident %)))))

(defn schemas->tx
  "Builds a Datomic transaction from a collection of prismatic schemas."
  [schemas]
  (let [txes (into [] build-tx-tf schemas)]
    (validate-txes txes)))

;; Database conformity

(defn- flatten-pull [x]
  (into {} (map (fn [[k v]] [k (if (map? v) (:db/ident v) v)])) x))

#?(:clj
   (defn- get-attributes [db idents]
     (->> (d/q '[:find [(pull ?e [:db/ident
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
               db idents)
          (map flatten-pull))))

(s/defschema ConformsResult
  {:conforms? s/Bool
   :missing [s/Keyword]
   :mismatch [[(s/one {s/Keyword s/Any} 'db-attr) (s/one {s/Keyword s/Any} 'schema-attr)]]})

#?(:clj
   (s/defn conforms? :- ConformsResult
     "Checks if database conforms to prismatc schemas

    Returns map containing:
    * :conforms? - booelan whether database conforms to schemas
    * :missing - vector of missing :db/ident values
    * :mismatch - vector of mismatched schema attributes for each datom. Each value is a tuple of [database-attr schema-attr]"
     [conn schemas]
     (let [txes (map #(dissoc % :db/id :db.install/_attribute) (schemas->tx schemas))
           db-attrs (get-attributes (d/db conn) (map :db/ident txes))]
       (loop [txes txes missing [] mismatch []]
         (if-let [tx (first txes)]
           (let [[db-attr] (filter #(= (:db/ident %) (:db/ident tx)) db-attrs)]
             (cond
               (nil? db-attr)
               (recur (rest txes) (conj missing (:db/ident tx)) mismatch)

               (not= db-attr tx)
               (recur (rest txes) missing (conj mismatch [db-attr tx]))

               :else (recur (rest txes) missing mismatch)))
           {:conforms? (and (empty? mismatch) (empty? missing))
            :missing missing
            :mismatch mismatch})))))

;; Database diffing

;; See "Altering Schema Attributes" - http://docs.datomic.com/schema.html
(defn- possible-alteration? [from to]
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

(def ^:private alterations
  [[:db/isComponent] [:db/cardinality] [:db/noHistory] [:db/index :db/unique] [:db/unique]])

(s/defschema Alterations {[(s/one {s/Keyword s/Any} 'from) (s/one {s/Keyword s/Any} 'to)] s/Bool})

(s/defn possible-alterations? [from to] :- Alterations
  (into {}
   (for [alteration alterations
         :let [from-value (select-keys from alteration)
               to-value (select-keys to alteration)]
         :when (or (not (empty? from-value)) (not (empty? to-value)))]
     [[from-value to-value] (possible-alteration? from-value to-value)])))

(s/defschema Diff
  {:diff [{s/Keyword s/Any}]
   :conflicts {s/Keyword Alterations}})

#?(:clj
   (s/defn db-diff :- Diff
     "Returns a map containing the difference between database and prismatic schemas:
    * :diff - a transaction of schemas not in database (exlcuding any schema attributes requiring alteration)
    * :conflicts - a map containing the schema attribute alterations required for each datom."
     [conn schemas]
     (let [txes (schemas->tx schemas)
           db-attrs (get-attributes (d/db conn) (map :db/ident txes))]
       (loop [txes txes next-txes [] alterations {}]
         (if-let [tx (first txes)]
           (let [[db-attr] (filter #(= (:db/ident %) (:db/ident tx)) db-attrs)
                 attr (dissoc tx :db/id :db/doc :db.install/_attribute)]
             (if (nil? db-attr)
               (recur (rest txes) (conj next-txes tx) alterations)
               (if (not= db-attr attr)
                 (let [[from to _] (diff db-attr attr)
                       next-alterations (assoc alterations (:db/ident tx) (possible-alterations? from to))]
                   (recur (rest txes) next-txes next-alterations))
                 (recur (rest txes) next-txes alterations))))
           {:diff next-txes :conflicts alterations})))))

(defprotocol IPull
  (get-value [this])
  (pattern [this]))

(defn schema->pull-query [schema]
  (reduce-kv (fn [m k v]
               (if-let [next-v (pattern (get-value v))]
                 (conj m {(get-key k) next-v})
                 (conj m (get-key k))))
             [] (get-value schema)))

(extend-protocol IPull
  DatomicMeta
  (pattern [_] nil)
  (get-value [this] (get-value (:t this)))
  schema.core.EqSchema
  (pattern [_] nil)
  (get-value [this] (get-value (:v this)))
  schema.core.One
  (pattern [_] nil)
  (get-value [this] (get-value (:schema this)))
  schema.core.Maybe
  (pattern [_] nil)
  (get-value [this] (get-value (:schema this)))
  #?(:clj clojure.lang.PersistentHashSet :cljs cljs.core.PersistentHashSet)
  (pattern [_] nil)
  (get-value [this] (get-value (first this)))
  #?(:clj clojure.lang.PersistentVector :cljs cljs.core.PersistentVector)
  (pattern [_] nil)
  (get-value [this] (get-value (first this)))

  schema.core.Recursive
  (get-value [this] this)
  (pattern [_] 1)
  schema.core.EnumSchema
  (get-value [this] this)
  (pattern [_] [:db/ident])
  #?(:clj clojure.lang.PersistentHashMap :cljs cljs.core.PersistentHashMap)
  (get-value [this] this)
  (pattern [this] (schema->pull-query this))
  #?(:clj clojure.lang.PersistentArrayMap :cljs cljs.core.PersistentArrayMap)
  (get-value [this] this)
  (pattern [this] (schema->pull-query this))

  #?@(:clj [java.lang.Class
            (pattern [this] nil)
            (get-value [this] this)])

  Object
  (pattern [this] nil)
  (get-value [this] this))

#?(:clj
   (defn pull-schema [db schema eid]
     (d/pull db (schema->pull-query schema) eid)))

#?(:clj
   (defn pull-many-schema [db schema eids]
     (d/pull-many db (schema->pull-query schema) eids)))
