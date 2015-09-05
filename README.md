# datomic-schema

Use your [url=https://github.com/Prismatic/schema]Prismatic schemas[/url] as Datomic (or datascript) database schemas!

datomic-schema allows you to describe the shape of your Datomic entities in your application code - removing duplication of defining your schemas twice.

datomic-schema works for Clojurescript too, allowing you to reuse the same schemas on the front end. This means it should work with Datascript (probably... I haven't tested yet)

You can read more about why we created this library here.

## Usage

Simply define your schemas

```clojure
(require [schema.core :as s]
         [datomic-schema.core :as ds]
         [datomic.api :as d])

(def Role (s/enum :user.role/user :user.role/admin))

(def User
  {:user/name s/Str
   :user/email s/Str
   :user/description (ds/datomic-meta {:fulltext true} s/Str)
   :user/role Role
   (s/optional-key :user/age) s/Num
   :user/friends [(s/recursive #'User)]
   :user/password s/Str})

(def schemas [User Role])

;; Build transaction EDN
(def schema-tx (schema->tx schemas))
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
@(d/transact conn schema-tx)
(conforms? conn schemas) ;; => true
```

## Gotchas

* Namespace each key, unless many entities refer to the same attribute in the database.
* No `:db.type/byte` implemented
* `schema.core.Predicate` maps to `:db.type/keyword` as `s/Keyword` returns an instance of `Predicate`. I have no idea how predicate functions would be used in database schemas, anyway.

## Migration strategy

`datomic-schema` does nothing to ensure that your application schemas match your database schema. Meaning it is up to the end user to handle migration.

A good approach is to spit the output of `schemas->tx` to a .edn file and use a library like conformity[https://github.com/rkneufeld/conformity] to handle schema migration.

As your application schemas change and become out of sync with your database, you will have to manually write the transactions to alter ...
To assist with this, `datomic-schema` has a function `conforms?` which checks if your prismatic schemas match the database.

Maybe it's possible to get the diff of a database and application schemas and build a transaction to sync database? An experiment for the future...

## License

Copyright © 2015

Distributed under the Eclipse Public License
