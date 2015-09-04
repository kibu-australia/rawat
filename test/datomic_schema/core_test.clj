(ns datomic-schema.core-test
  (:use midje.sweet)
  (:require [schema.core :as s]
            [datomic.api :as d]
            [datomic-schema.core :refer :all]))

(def MockSchema
  {:some/ref [{:some/nested-ref s/Bool}]
   :some/string s/Str
   :some/many-date [s/Inst]
   :some/keyword s/Keyword
   :some/full-text-string (datomic-meta {:db/fulltext true} s/Str)
   :some/enum (s/enum :enum/first :enum/second :enum/third)
   :some/many-enum [(s/enum :enum/fourth :enum/fifth)]
   (s/optional-key :some/optional-recursive-key) (s/recursive #'MockSchema)})

(fact
 (into [] build-schema MockSchema) =>
 (just [{:db/ident :some/ref
         :db/valueType :db.type/ref
         :db/cardinality :db.cardinality/many}
        {:db/ident :some/nested-ref
         :db/valueType :db.type/boolean
         :db/cardinality :db.cardinality/one}
        {:db/ident :some/string
         :db/valueType :db.type/string
         :db/cardinality :db.cardinality/one}
        {:db/ident :some/many-date
         :db/cardinality :db.cardinality/many
         :db/valueType :db.type/instant}
        {:db/ident :some/keyword
         :db/valueType :db.type/keyword
         :db/cardinality :db.cardinality/one}
        {:db/ident :some/full-text-string
         :db/cardinality :db.cardinality/one
         :db/valueType :db.type/string
         :db/fulltext true}
        {:db/ident :some/enum
         :db/cardinality :db.cardinality/one
         :db/valueType :db.type/ref}
        {:db/ident :enum/first}
        {:db/ident :enum/second}
        {:db/ident :enum/third}
        {:db/ident :enum/fourth}
        {:db/ident :enum/fifth}
        {:db/ident :some/many-enum
         :db/valueType :db.type/ref
         :db/cardinality :db.cardinality/many}
        {:db/ident :some/optional-recursive-key
         :db/valueType :db.type/ref
         :db/cardinality :db.cardinality/one}]
       :in-any-order))
