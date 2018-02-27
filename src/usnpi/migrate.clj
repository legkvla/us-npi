(ns usnpi.migrate
  (:require [usnpi.db :as db]
            [clojure.tools.logging :as log]))

(def ^:private
  migrations
  [{:name "Add tasks table"
    :sql "
CREATE TABLE IF NOT EXISTS tasks (
    id          serial primary key,
    handler     text not null,
    interval    integer not null default 0,
    last_run_at timestamp with time zone null,
    next_run_at timestamp with time zone not null,
    success     boolean not null default false,
    message     text not null default ''
);"}])

(defn migrate []
  (log/info "Running migrations...")

  (doseq [mig migrations]
    (log/infof "Migration: %s" (:name mig))
    (db/execute! (:sql mig)))

  (log/info "Done."))
