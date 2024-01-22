(ns fulcro-todomvc.app
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing :as btxn]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as sync]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [taoensso.tufte :as t]))

(defonce remote #_(fws/fulcro-websocket-remote {:auto-retry?        true
                                                :request-timeout-ms 10000}) (http/fulcro-http-remote {}))

#_(defonce app (sync/with-synchronous-transactions
                 (app/fulcro-app {:remotes {:remote remote}})
                 #{:remote}))

(defonce app (do
               (t/add-basic-println-handler! {})
               (with-react18
                 (app/fulcro-app {:remotes {:remote remote}}))))

#_(defonce app (app/fulcro-app {:remotes {:remote remote}}))

