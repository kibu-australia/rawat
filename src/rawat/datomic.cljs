(ns rawat.datomic
  (:require [cljs.reader :as reader]))

(defrecord DbId [part idx])

;; transit writer handler
(deftype DbIdHandler []
  Object
  (tag [this v] "db/id")
  (rep [this v] #js [(.-part v) (.-idx v)])
  (stringRep [this v] nil))

(defn tempid [part]
  (DbId. part (* -1 (.now js/Date))))

(defn tempid? [id]
  (if id
    (if (number? id)
      (neg? id)
      (if-let [idx (:idx id)]
        (neg? idx)
        false))
    true))

(defn- reader-str->dbid [s]
  (let [[part idx] (reader/read-string s)]
    (DbId. part idx)))

(defn- pr-dbid [d]
  (pr-str [(:part d) (:idx d)]))

(defn- dbid->reader-str [d]
  (str "#db/id " (pr-dbid d)))

(cljs.reader/register-tag-parser! "db/id" reader-str->dbid)

(extend-protocol IPrintWithWriter
  DbId
  (-pr-writer [d out opts]
    (-write out (dbid->reader-str d))))

(def transit-writers {DbId (DbIdHandler.)})
(def transit-readers {"db/id" (fn [[part idx]] (DbId. part idx))})
