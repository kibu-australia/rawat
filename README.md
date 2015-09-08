# rawat

> Nature does not know extinction.
> All it knows is transformation
> - Wernher Von Braun


[![Build Status](https://travis-ci.org/kibu-australia/rawat.svg)](https://travis-ci.org/kibu-australia/rawat)

Use your [Prismatic schemas](https://github.com/Prismatic/schema) as Datomic database schemas!

rawat allows you to describe the shape of your Datomic entities within your application code - removing the duplication of defining your schemas in multiple places.

rawat works in clojurescript too, allowing you to reuse the same schemas on the front end!

You can read more about why we created this library and how we use it here.

## Usage

[![Clojars Project](http://clojars.org/kibu/rawat/latest-version.svg)](http://clojars.org/kibu/rawat)

```clojure
(require [schema.core :as s]
         [rawat.core :as ds]
         [datomic.api :as d])

;; Define your schemas!

(def Role (s/enum :user.role/user :user.role/admin))

(def User
  {:user/name s/Str
  :user/email s/Str
  ;; If you need to include extra schema attributes for your datom, use `ds/datomic-meta`
  :user/description (ds/datomic-meta {:fulltext true} s/Str)
  :user/role Role
  (s/optional-key :user/age) s/Num
  :user/friends [(s/recursive #'User)]
  :user/password s/Str})

(def schemas [User Role])

;; Build transaction EDN

(def schemas-tx (schemas->tx schemas))
;; =>
[{:db/ident :user/name,
  :db/cardinality :db.cardinality/one,
  :db/valueType :db.type/string,
  :db/id {:part :db.part/db, :idx -1000911},
  :db.install/_attribute :db.part/db}
 {:db/ident :user/email,
  :db/cardinality :db.cardinality/one,
  :db/valueType :db.type/string,
  :db/id {:part :db.part/db, :idx -1000912},
  :db.install/_attribute :db.part/db}
 {:db/ident :user/role,
  :db/cardinality :db.cardinality/one,
  :db/valueType :db.type/ref,
  :db/id {:part :db.part/db, :idx -1000913},
  :db.install/_attribute :db.part/db}
 {:db/ident :user.role/admin,
  :db/id {:part :db.part/db, :idx -1000914}}
 {:db/ident :user.role/user, :db/id {:part :db.part/db, :idx -1000915}}
 {:db/ident :user/age,
  :db/cardinality :db.cardinality/one,
  :db/valueType :db.type/long,
  :db/id {:part :db.part/db, :idx -1000916},
  :db.install/_attribute :db.part/db}
 {:db/ident :user/friends,
  :db/cardinality :db.cardinality/many,
  :db/valueType :db.type/ref,
  :db/id {:part :db.part/db, :idx -1000917},
  :db.install/_attribute
 :db.part/db}
 {:db/ident :user/password,
  :db/cardinality :db.cardinality/one,
  :db/valueType :db.type/string,
  :db/id {:part :db.part/db, :idx -1000918},
  :db.install/_attribute :db.part/db}]

;; And finally transact to database!
(d/create-database "datomic:dev://test")
(def conn (d/connect "datomic:dev://test")
@(d/transact conn schemas-tx)

(conforms? conn schemas) ;; => {:conforms? true :missing [] :mismatch []}

;; Let's add a new schema
(def Country
  {:country/name s/Str})

(def schemas [User Role Country]

(conforms? conn schemas) ;; => {:conforms? false :missing [:country/name] :mismatch []}
(def diff (db-diff conn schemas)) ;; => {:diff [] :conflicts []}

@(d/transact conn (:diff diff))

(conforms? conn schemas) ;; => {:conforms? true :missing [] :mismatch []}

;; Altering existing schema (a user can now have many roles and has a new attribute, )

(def User
  (assoc User :user/role [Role]))

(conforms? conn schemas) ;; => {:conforms? false :missing [] :mismatch [...]}

(db-diff conn schemas) ;; => {:diff [] :conflicts []}

```

## Gotchas

* Namespace each key, unless many entities refer to the same datom in the database.
* No `:db.type/byte` type implemented
* `schema.core.Predicate` maps to `:db.type/keyword`. This is because `s/Keyword` returns an instance of `Predicate`. I have no idea how predicate functions would be used in database schemas, anyway.
* `s/eq` assumes reference to an enumerated value

## Util functions

### Prismatc schema helpers

If you want to slurp an entire namespace of schemas, the convience function `ns->schemas` is available.

This will only slurp schemas defined using `s/defschema`.

```clojure
;; Slurps up namespace foo.schemas
(ns->schemas 'foo.schemas)
```

### Datomic helpers

`possible-alterations?` checks whether the alteration of a schema attribute is possible.

This returns a map where the key is a tuple `[from to]` and value is a boolean.

```clojure
;; From :db.cardinality/many to :db/cardinality/one
(possible-alterations? {:db/cardinality :db.cardinality/many} {:db/cardinality :db.cardinality/one})
;; => {[{:db/cardinality :db.cardinality/many} {:db/cardinality :db.cardinality/one}] true}
```

## Migration strategy

`rawat` does nothing to ensure that your application schemas match your database schema. Meaning it is up to the end user to handle migration.

A good approach is to spit the output of `schemas->tx` to a .edn file and use a library like [conformity](https://github.com/rkneufeld/conformity) to handle schema migration.

As your application schemas change and become out of sync with your database, you will have to manually manage the migrations.

To assist with this, `rawat` has a function `conforms?` which checks if your prismatic schemas match the database.

### Diffing



`db-diff` is available which returns a map of differences between schemas and database.

```clojure

```


## License

Copyright © 2015

Distributed under the Eclipse Public License
