(ns datomic-schema.core
  (:require [schema.core :as s]
            [datomic.api :as d])
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
       ;; else, no metadata cos cannot handle data type
       {})))

#?(:clj
   (extend-protocol IDatomicSchema
     java.lang.Class (get-attrs [this] (class->datomic-type this))
     java.lang.Boolean (get-attrs [_] )
     clojure.lang.PersistentArrayMap (get-attrs [_] {:db/valueType :db.type/ref})
     schema.core.Recursive (get-attrs [_] {:db/valueType :db.type/ref})
     schema.core.Predicate (get-attrs [_] {:db/valueType :db.type/keyword}) ;; s/Keyword returns s/pred
     clojure.lang.PersistentHashSet (get-attrs [this] (get-attrs (vec this)))
     clojure.lang.PersistentVector (get-attrs [this]
                                     (merge {:db/cardinality :db.cardinality/many}
                                            (get-attrs (first this))))
     schema.core.EnumSchema (get-attrs [this]
                              ;; Returns each enumeration as ident
                              (let [[idents] (vals this)]
                                (map (fn [ident] {:db/ident ident}) idents)))
     Object (get-attrs [_] {})) ;; By default, no schema
   ;; TODO: write cljs equiv
   :cljs (extend-protocol IDatomicSchema (get-attrs [_] {})))

(defn conforms? [db & schemas]

  )

(defn build-schema [schema]

  )

(defn build-schema [& schemas]



  )
