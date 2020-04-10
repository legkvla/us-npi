(ns usnpi.search
  (:require [usnpi.http :as http]
            [usnpi.db :as db]
            [usnpi.npi :as npi]
            [honeysql.format :as sqlf]
            [clojure.string :as str]))

(defmacro resource [& fields]
  (let [s (str "resource#>>'{"
               (str/join "," (map (fn [field]
                                    (if (keyword field)
                                      (name field)
                                      field))
                                  fields))
               "}'")]
    `(db/raw ~s)))

(defn build-where [where {:keys [ids postal-codes state city]}]
  (cond-> where
    ids          (conj [:in    :id ids])
    city         (conj [:ilike (resource :address 0 :city) (str "%" city "%")])
    state        (conj [:=     (resource :address 0 :state) state])
    postal-codes (conj [:in    (resource :address 0 :postalCode) postal-codes])))

(defn only-organization? [{:keys [name org first-name last-name]}]
  (and org (not name) (not first-name) (not last-name)))

(defn only-practitioner? [{:keys [name org first-name last-name]}]
  (and (or first-name last-name) (not name) (not org)))

(defn with-count [query {:keys [count]}]
  (cond-> query
    count (assoc :limit count)))

(defn with-type [query params pred type]
  (if (pred params)
    query
    (update query :select conj [type :type])))

(defn taxonomy-check [taxonomies]
   (db/raw (str "resource @?? '$.qualification[*].code.coding[*].code ? (" (str/join " || " (for [t taxonomies] (str "@ == \"" t "\""))) ")'::jsonpath")))

(defn first-name-check [first-name]
  (db/raw (str "resource @?? '$.name[*].given[*] ? (@ starts with \"" (str/upper-case first-name) "\")'::jsonpath")))

(defn family-name-check [family-name]
  (db/raw (str "resource @?? '$.name[*].family ? (@ starts with \"" (str/upper-case family-name) "\")'::jsonpath")))

(defn build-practitioner-sql [{:keys [name first-name last-name taxonomies] :as params}]
  (when-not (only-organization? params)
    (let [family     (or name last-name)
          first-name (and (not name) first-name)
          name-col   (resource :name 0 :family)]
      (-> {:select [:id :resource [name-col :name]]
           :from [:practitioner]
           :where (cond-> [:and [:= :deleted false]]
                    family       (conj (family-name-check family))
                    first-name   (conj (first-name-check first-name))
                    taxonomies   (conj (taxonomy-check taxonomies))
                    :always      (build-where params))}
          (with-count params)
          (with-type params only-practitioner? 1)))))

(defn build-organization-sql [{:keys [name org count taxonomies] :as params}]
  (when-not (only-practitioner? params)
    (let [org (or name org)
          name-col (resource :name)]
      (-> {:select [:id :resource [name-col :name]]
           :from [:organizations]
           :where (cond-> [:and [:= :deleted false]]
                    org          (conj [:ilike name-col (str "%" org "%")])
                    taxonomies   (conj (taxonomy-check taxonomies))
                    :always      (build-where params))}
          (with-count params)
          (with-type params only-organization? 2)))))

(defn wrap-query [query]
  {:select [:id :resource]
   :from [[query :q]]
   :order-by [:name]})

(defmethod sqlf/format-clause :union-practitioner-and-organization [[_ [left right]] _]
  (str "(" (sqlf/to-sql left) ") union all (" (sqlf/to-sql right) ")"))

(defn union [practitioner-sql organization-sql]
  {:select [:id :resource]
   :from [[{:union-practitioner-and-organization [practitioner-sql
                                                  organization-sql]} :q]]
   :order-by [:type :name]})

(defn as-vector [s]
  (when-not (str/blank? s)
    (str/split s #",")))

(defn normalize-count [count]
  (or count 50))

(defn search [{params :params}]
  (let [params (-> params
                   (update :ids as-vector)
                   (update :postal-codes as-vector)
                   (update :taxonomies as-vector)
                   (update :count normalize-count))
        p-sql (build-practitioner-sql params)
        o-sql (build-organization-sql params)
        sql (cond
              (and p-sql o-sql) (union p-sql o-sql)
              p-sql             (wrap-query p-sql)
              o-sql             (wrap-query o-sql))]

    ; (println ">>> Search params: " params)
    ; (println ">>> p-sql: " p-sql)
    ; (println ">>> o-sql: " o-sql)

    (http/http-resp (npi/as-bundle (db/query (db/to-sql sql))))))
