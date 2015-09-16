(ns rawat.core-test
  (:use midje.sweet)
  (:require [schema.core :as s]
            [datomic.api :as d]
            [rawat.core :refer :all]))

(def EnumSchema
  (s/enum :enum/first :enum/second :enum/third))

(def MockSchema
  {:some/ref [{:some/nested-ref s/Bool}]
   :some/string s/Str
   :some/many-date [s/Inst]
   :some/keyword s/Keyword
   (datomic-meta :some/full-text-string {:db/fulltext true}) s/Str
   :some/enum EnumSchema
   :some/many-enum [(s/enum :enum/fourth :enum/fifth)]
   (s/optional-key :some/optional-recursive-key) (s/recursive #'MockSchema)})

;; TODO: add :db/id and :db.install/_attribute vals
(def mock-schema-tx-with-ids
  [(just {:db/ident :some/ref
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/many})
   (just {:db/ident :some/nested-ref
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/valueType :db.type/boolean
          :db/cardinality :db.cardinality/one})
   (just {:db/ident :some/string
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one})
   (just {:db/ident :some/many-date
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/cardinality :db.cardinality/many
          :db/valueType :db.type/instant})
   (just {:db/ident :some/keyword
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/valueType :db.type/keyword
          :db/cardinality :db.cardinality/one})
   (just {:db/ident :some/full-text-string
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/cardinality :db.cardinality/one
          :db/valueType :db.type/string
          :db/fulltext true})
   (just {:db/ident :some/enum
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/cardinality :db.cardinality/one
          :db/valueType :db.type/ref})
   (just {:db/ident :enum/first
          :db/id anything})
   (just {:db/ident :enum/second
          :db/id anything})
   (just {:db/ident :enum/third
          :db/id anything})
   (just {:db/ident :enum/fourth
          :db/id anything})
   (just {:db/ident :enum/fifth
          :db/id anything})
   (just {:db/ident :some/many-enum
          :db/valueType :db.type/ref
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/cardinality :db.cardinality/many})
   (just {:db/ident :some/optional-recursive-key
          :db/valueType :db.type/ref
          :db/id anything
          :db.install/_attribute :db.part/db
          :db/cardinality :db.cardinality/one})])

(facts "about validate-txes"
 (fact (validate-txes (into [] build-schema-tf MockSchema)) =>
       (into [] build-schema-tf MockSchema))

 ;; Conflicting ident
 (fact (validate-txes (conj (into [] build-schema-tf MockSchema)
                            {:db/ident :some/many-enum
                             :db/cardinality :db.cardinality/one
                             :db/valueType :db.type/string}))
       => throws Exception)

 ;; Duplicate ident
 (validate-txes (conj (into [] build-schema-tf MockSchema)
                      {:db/ident :some/many-enum
                       :db/cardinality :db.cardinality/many
                       :db/valueType :db.type/ref}))
 => (into [] build-schema-tf MockSchema))

(facts "about schemas->tx"
 (fact
  (schemas->tx [MockSchema EnumSchema])
  => (just mock-schema-tx-with-ids :in-any-order)))

(def uri "datomic:mem://test")

(with-state-changes
  [(before :facts (d/create-database uri))
   (after :facts (d/delete-database uri))]

  (facts
   "About transactions"
   (let [conn (d/connect uri)]
      @(d/transact conn (schemas->tx MockSchema)) => truthy

      (conforms? conn MockSchema)
      => {:conforms? true :missing [] :mismatch []}

      (conforms? conn [MockSchema {:not-in-database s/Bool}])
      => {:conforms? false :missing [:not-in-database] :mismatch []}

      @(d/transact conn [{:db/id :some/many-enum                ;; Altering datom :some/many-enum from
                          :db/cardinality :db.cardinality/one   ;; cardinality of many to cardinality of one
                          :db.alter/_attribute :db.part/db}])   ;; should mean schemas no longer conform to db!
      => truthy

      (conforms? conn MockSchema)
      => {:conforms? false
          :missing []
          :mismatch [[{:db/ident :some/many-enum
                       :db/cardinality :db.cardinality/one
                       :db/valueType :db.type/ref}
                      {:db/ident :some/many-enum
                       :db/cardinality :db.cardinality/many
                       :db/valueType :db.type/ref}]]}

      ;; From one to many is possible
      (db-diff conn MockSchema)
      => {:diff []
          :conflicts {:some/many-enum {[{:db/cardinality :db.cardinality/one}
                                        {:db/cardinality :db.cardinality/many}]
                                       true}}}

      (db-diff conn (assoc MockSchema :not-in-db s/Num))
      => (just {:diff (just [(just {:db/ident :not-in-db
                                    :db/id truthy
                                    :db/cardinality :db.cardinality/one
                                    :db.install/_attribute :db.part/db
                                    :db/valueType :db.type/long})])
                :conflicts {:some/many-enum {[{:db/cardinality :db.cardinality/one}
                                              {:db/cardinality :db.cardinality/many}]
                                             true}}}))))
