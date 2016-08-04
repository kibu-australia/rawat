(ns rawat.datomic
  (:require [cljs.reader :as reader]))

(defrecord DbId [part idx])

;; transit writer handler
(deftype DbIdHandler []
  Object
  (tag [this v] "db/id")
  (rep [this v] #js [(.-part v) (.-idx v)])
  (stringRep [this v] nil))

(def ^:private counter (atom 0))

(defn tempid [part]
  (let [id (DbId. part (* -1000000 @counter))]
    (swap! counter inc)
    id))

(defn tempid? [id]
  (cond
    (number? id)        (neg? id)
    (instance? DbId id) (neg? (:idx id))
    :else false))

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

(def transit-writer {DbId (DbIdHandler.)})
(def transit-reader {"db/id" (fn [[part idx]] (DbId. part idx))})
