(ns com.fulcrologic.fulcro.malli-specs
  (:require
    [malli.core :as m]
    [malli.error :as me]
    [com.fulcrologic.guardrails.malli.core :refer [>def]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]))

;; ================================================================================
;; Transaction Specs
;; ================================================================================

(>def :com.fulcrologic.fulcro.application/remote-name :keyword)
(>def :com.fulcrologic.fulcro.application/remote-names [:set :keyword])
(>def :com.fulcrologic.fulcro.application/remotes [:map-of :com.fulcrologic.fulcro.application/remote-name map?])
(>def :com.fulcrologic.fulcro.application/active-remotes [:set :keyword])
(>def :com.fulcrologic.fulcro.application/runtime-atom [:fn futil/atom?])
(>def :com.fulcrologic.fulcro.application/state-atom [:fn futil/atom?])
(>def :com.fulcrologic.fulcro.application/app [:map {:closed false}
                                               :com.fulcrologic.fulcro.application/state-atom
                                               :com.fulcrologic.fulcro.application/runtime-atom])

(>def :edn-query-language.ast/node map?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/id uuid?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/idx int?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/created inst?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/started inst?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/finished inst?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx [:vector :any])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/abort-id any?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/options [:map {:closed? false}
                                                                [:com.fulcrologic.fulcro.algorithms.tx-processing/abort-id {:optional true}]
                                                                [:abort-id {:optional true} :com.fulcrologic.fulcro.algorithms.tx-processing/abort-id]])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/started? set?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/complete? set?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/results [:map-of keyword? any?])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/progress [:map-of keyword? any?])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/transmitted-ast-nodes [:map-of keyword? :edn-query-language.ast/node])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch map?) ; a tree is also a node
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/ast map?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node :com.fulcrologic.fulcro.algorithms.tx-processing/ast)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/desired-ast-nodes [:map-of keyword? :edn-query-language.ast/node])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element [:map {:closed false}
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/idx
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/started?
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/complete?
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/results
                                                                   :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch
                                                                   [:com.fulcrologic.fulcro.algorithms.tx-processing/desired-ast-nodes {:optional true}]
                                                                   [:com.fulcrologic.fulcro.algorithms.tx-processing/transmitted-ast-nodes {:optional true}]
                                                                   [:com.fulcrologic.fulcro.algorithms.tx-processing/progress {:optional true}]])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/elements [:vector :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node
  [:map {:closed false}
   :com.fulcrologic.fulcro.algorithms.tx-processing/id
   :com.fulcrologic.fulcro.algorithms.tx-processing/created
   :com.fulcrologic.fulcro.algorithms.tx-processing/options
   :com.fulcrologic.fulcro.algorithms.tx-processing/tx
   :com.fulcrologic.fulcro.algorithms.tx-processing/elements
   [:com.fulcrologic.fulcro.algorithms.tx-processing/started {:optional true}]
   [:com.fulcrologic.fulcro.algorithms.tx-processing/finished {:optional true}]])

(>def :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler 'ifn?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler 'ifn?)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/active? :boolean)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/parallel? :boolean)

(>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-node
  [:map {:closed false}
   :com.fulcrologic.fulcro.algorithms.tx-processing/id
   :com.fulcrologic.fulcro.algorithms.tx-processing/idx
   :com.fulcrologic.fulcro.algorithms.tx-processing/ast
   :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler
   :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler
   :com.fulcrologic.fulcro.algorithms.tx-processing/active?
   [:com.fulcrologic.fulcro.algorithms.tx-processing/options {:optional true}]])

(>def :com.fulcrologic.fulcro.algorithms.tx-processing/submission-queue [:vector :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/active-queue [:vector :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue [:vector :com.fulcrologic.fulcro.algorithms.tx-processing/send-node])
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queues [:map-of :com.fulcrologic.fulcro.application/remote-name :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue])

(>def :com.fulcrologic.fulcro.algorithms.tx-processing/activation-scheduled? :boolean)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/sends-scheduled? :boolean)
(>def :com.fulcrologic.fulcro.algorithms.tx-processing/queue-processing-scheduled? :boolean)

(comment
  (m/validate :com.fulcrologic.fulcro.algorithms.tx-processing/send-node {:com.fulcrologic.fulcro.algorithms.tx-processing/id             (java.util.UUID/randomUUID)
                                                                          :com.fulcrologic.fulcro.algorithms.tx-processing/idx            3
                                                                          :com.fulcrologic.fulcro.algorithms.tx-processing/ast            {}
                                                                          :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler (fn [])
                                                                          :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler (fn [])
                                                                          :com.fulcrologic.fulcro.algorithms.tx-processing/active?        false
                                                                          }))
