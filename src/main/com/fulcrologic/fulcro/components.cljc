(ns com.fulcrologic.fulcro.components
  #?(:cljs (:require-macros com.fulcrologic.fulcro.components))
  (:require
    #?@(:clj
        [[cljs.analyzer :as ana]
         [cljs.env :as cljs-env]]
        :cljs
        [[goog.object :as gobj]
         ["react" :as react]])
    [edn-query-language.core :as eql]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [clojure.walk :refer [prewalk]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.core :refer [>def]]
    [clojure.set :as set])
  #?(:clj
     (:import
       [clojure.lang Associative IDeref APersistentMap])))

#?(:clj
   (defn current-config []
     (let [config (some-> cljs-env/*compiler* deref (get-in [:options :external-config :fulcro]))]
       config)))

(def ^:dynamic *app* nil)
(def ^:dynamic *parent* nil)
(def ^:dynamic *shared* nil)
(def ^:private force-refresh (volatile! false))

(defn enable-forced-refresh!
  "When called this enables forced refresh for the given amount of time in ms. (Does nothing in CLJ)"
  [ms]
  #?(:clj  nil
     :cljs (do
             (vreset! force-refresh true)
             (js/setTimeout (fn [] (vreset! force-refresh false)) ms))))

(defn force-refresh?
  "Returns true if a rendering request wants all components to render even if their props and state have not changed."
  []
  @force-refresh)

(def isoget-in
  "
  [obj kvs]
  [obj kvs default]

  Like get-in, but for js objects, and in CLJC. In clj, it is just get-in. In cljs it is
  gobj/getValueByKeys."
  rc/isoget-in)

(def isoget
  "
  [obj k]
  [obj k default]

  Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  rc/isoget)

(def register-component!
  "
  [k component-class]

  Add a component to Fulcro's component registry.  This is used by defsc to ensure that all Fulcro classes
  that have been compiled (transitively required) will be accessible for lookup by fully-qualified symbol/keyword.
  Not meant for public use, unless you're creating your own component macro that doesn't directly leverage defsc."
  rc/register-component!)

(defn force-children
  "Utility function that will force a lazy sequence of children (recursively) into realized
  vectors (React cannot deal with lazy seqs in production mode)"
  [x]
  (cond->> x
    (seq? x) (into [] (map force-children))))

(def newer-props
  "
  [props-a props-b]

  Returns whichever of the given Fulcro props were most recently generated according to `denormalization-time`. This
  is part of props 'tunnelling', an optimization to get updated props to instances without going through the root."
  rc/newer-props)

(defn component-instance?
  "[x]

   Returns true if the argument is a component. A component is defined as a *mounted component*.
   This function returns false for component classes, and also returns false for the output of a Fulcro component factory."
  #?(:cljs {:tag boolean})
  [x]
  (rc/component-instance? x))

(def component?
  "[x]

   Returns true if the argument is a component instance.

   DEPRECATED for terminology clarity. Use `component-instance?` instead."
  component-instance?)

(defn component-class?
  "Returns true if the argument is a component class."
  #?(:cljs {:tag boolean})
  [x]
  (rc/component-class? x))

(>def ::component-class component-class?)

(def component-name
  "[class]

   Returns a string version of the given react component's name. Works on component instances and classes."
  rc/component-name)

(def class->registry-key
  "[class]

   Returns the registry key for the given component class."
  rc/class->registry-key)

(def registry-key->class
  "[classname]

  Look up the given component in Fulcro's global component registry. Will only be able to find components that have
  been (transitively) required by your application.

  `classname` can be a fully-qualified keyword, string, symbol, or component class. In the latter
  case this function just acts as `identity`. This allows this function to act as a coercion
  that ensures you have a class."
  rc/registry-key->class)

(def computed
  "
  [props computed-map]

  Add computed properties to props. This will *replace* any pre-existing computed properties. Computed props are
  necessary when a parent component wishes to pass callbacks or other data to children that *have a query*. This
  is not necessary for \"stateless\" components, though it will work properly for both.

  Computed props are \"remembered\" so that a targeted update (which can only happen on a component with a query
  and ident) can use new props from the database without \"losing\" the computed props that were originally passed
  from the parent. If you pass things like callbacks through normal props, then targeted updates will seem to \"lose
  track of\" them.
  "
  rc/computed)

(def get-computed
  "[this-or-props]
   [this-or-props k-or-ks]

   Return the computed properties on a component or its props. Note that it requires that the normal properties are not nil."
  rc/get-computed)

(defn get-extra-props
  "Get any data (as a map) that props extensions have associated with the given Fulcro component. Extra props will
  be empty unless you've installed props-middleware (on your app) that sets them."
  [this]
  (isoget-in this [:props :fulcro$extra_props] {}))

(def props
  "[this]

   Return a component's props."
  rc/props)

(defn children
  "[this]

   Get the sequence of react children of the given component."
  [component]
  (let [cs #?(:clj (get-in component [:children])
              :cljs (gobj/getValueByKeys component "props" "children"))]
    (if (or (coll? cs) #?(:cljs (array? cs))) cs [cs])))

(defn react-type
  "Returns the component type, regardless of whether the component has been
   mounted"
  [x]
  (rc/component-type x))

(def get-class
  "[instance]

   Returns the react type (component class) of the given React element (instance). Is identity if used on a class."
  rc/get-class)

(def component-options
  "[component & ks]

   Returns the map of options that was specified (via `defsc`) for the component class."
  rc/component-options)

(defn has-feature?
  "Returns true if the component has `option-key` declared in the component options map."
  #?(:cljs {:tag boolean}) [component option-key]
  (contains? (component-options component) option-key))
(defn has-initial-app-state?
  "Returns true if the component has initial app state."
  #?(:cljs {:tag boolean}) [component]
  (has-feature? component :initial-state))
(defn has-ident?
  "Returns true if the component has an ident"
  #?(:cljs {:tag boolean}) [component] (has-feature? component :ident))
(defn has-query?
  "Returns true if the component has a query"
  #?(:cljs {:tag boolean}) [component] (has-feature? component :query))
(defn has-pre-merge?
  "Returns true if the component has a pre-merge"
  #?(:cljs {:tag boolean}) [component] (has-feature? component :pre-merge))
(defn ident
  "Returns the ident that would be generated by the given component instance or class IF it was supplied props"
  [this props] (when (has-feature? this :ident) ((component-options this :ident) this props)))
(defn query
  "Returns the STATIC query of the fgiven component"
  [this] (when (has-feature? this :query) ((component-options this :query) this)))
(defn initial-state
  "Returns the initial state of component clz if it was passed the given params"
  [clz params] (when (has-feature? clz :initial-state) ((component-options clz :initial-state) params)))
(defn pre-merge [this data] (when (has-feature? this :pre-merge) ((component-options this :pre-merge) data)))
(declare get-parent)
(defn depth
  "Returns the depth of this from root."
  [this]
  (loop [p     (get-parent this)
         depth 0]
    (if (and p (< depth 1000))
      (recur (get-parent p) (inc depth))
      depth)))

(defn get-raw-react-prop
  "GET a RAW react prop. Used internally. Safe in CLJC, but equivalent to `(gobj/getValueByKeys this \"props\" (name k)`."
  [c k]
  (isoget-in c [:props k]))

(defn any->app
  "Attempt to coerce `x` to an app.  Legal inputs are a fulcro application, a mounted component,
  or an atom holding any of the above."
  [x]
  (rc/any->app x))

(defn raw->newest-props
  "Using raw react props/state returns the newest Fulcro props. This is part of \"props tunneling\", where component
  local state is leveraged as a communication mechanism of updated props directly to a component that has an ident.
  This function will return the correct version of props based on timestamps."
  [raw-props raw-state]
  #?(:clj  raw-props
     :cljs (let [next-props (gobj/get raw-props "fulcro$value")
                 opt-props  (gobj/get raw-state "fulcro$value")]
             (newer-props next-props opt-props))))

(defn shared
  "Return the global shared properties of the root. See :shared and
   :shared-fn app options. NOTE: Shared props only update on root render and by explicit calls to
   `app/update-shared!`.

   This function attempts to rely on the dynamic var *shared* (first), but will make a best-effort of
   finding shared props when run within a component's render or lifecycle. Passing your app will
   ensure this returns the current shared props."
  ([comp-or-app]
   (shared comp-or-app []))
  ([comp-or-app k-or-ks]
   (let [shared (some-> (any->app comp-or-app) :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/shared-props)
         ks     (cond-> k-or-ks
                  (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(letfn
  [(wrap-props-state-handler
     ([handler]
      (wrap-props-state-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj (fn [& args] (apply handler args))
         :cljs
         (fn [raw-props raw-state]
           (this-as this
             (let [props (if check-for-fresh-props-in-state?
                           (raw->newest-props raw-props raw-state)
                           (gobj/get raw-props "fulcro$props"))
                   state (gobj/get raw-state "fulcro$state")]
               (handler this props state)))))))
   (static-wrap-props-state-handler
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [raw-props raw-state]
          (let [props (raw->newest-props raw-props raw-state)
                state (gobj/get raw-state "fulcro$state")]
            (handler props state)))))
   (should-component-update?
     [raw-next-props raw-next-state]
     #?(:clj true
        :cljs (if (force-refresh?)
                true
                (this-as this
                  (let [current-props (props this)
                        next-props    (raw->newest-props raw-next-props raw-next-state)
                        next-state    (gobj/get raw-next-state "fulcro$state")
                        current-state (gobj/getValueByKeys this "state" "fulcro$state")
                        next-children (gobj/get raw-next-props "children")]
                    (or                                     ; these are not in the let because they will short-circuit, and are not necessarily cheap
                      (not= current-state next-state)
                      (not= (gobj/getValueByKeys this "props" "children") next-children)
                      (not= current-props next-props)))))))
   (component-did-update
     [raw-prev-props raw-prev-state snapshot]
     #?(:cljs
        (this-as this
          (let [{:keys [ident componentDidUpdate]} (component-options this)
                prev-state (gobj/get raw-prev-state "fulcro$state")
                prev-props (raw->newest-props raw-prev-props raw-prev-state)]
            (when componentDidUpdate
              (componentDidUpdate this prev-props prev-state snapshot))
            (when ident
              (let [old-ident        (ident this prev-props)
                    next-ident       (ident this (props this))
                    app              (any->app this)
                    drop-component!  (ah/app-algorithm app :drop-component!)
                    index-component! (ah/app-algorithm app :index-component!)]
                (when (not= old-ident next-ident)
                  (drop-component! this old-ident)
                  (index-component! this))))))))
   (component-did-mount
     []
     #?(:cljs
        (this-as this
          (gobj/set this "fulcro$mounted" true)
          (let [{:keys [componentDidMount]} (component-options this)
                app              (any->app this)
                index-component! (ah/app-algorithm app :index-component!)]
            (index-component! this)
            (when componentDidMount
              (componentDidMount this))))))
   (component-will-unmount []
     #?(:cljs
        (this-as this
          (let [{:keys [componentWillUnmount]} (component-options this)
                app             (any->app this)
                drop-component! (ah/app-algorithm app :drop-component!)]
            (when componentWillUnmount
              (componentWillUnmount this))
            (gobj/set this "fulcro$mounted" false)
            (drop-component! this)))))
   (wrap-this
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [& args] (this-as this (apply handler this args)))))
   (wrap-props-handler
     ([handler]
      (wrap-props-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj #(handler %1)
         :cljs
         (fn [raw-props]
           (this-as this
             (let [raw-state (.-state this)
                   props     (if check-for-fresh-props-in-state?
                               (raw->newest-props raw-props raw-state)
                               (gobj/get raw-props "fulcro$props"))]
               (handler this props)))))))

   (wrap-base-render [render]
     #?(:clj (fn [& args]
               (binding [*parent* (first args)]
                 (apply render args)))
        :cljs
        (fn [& args]
          (this-as this
            (if-let [app (any->app this)]
              (binding [*app*    app
                        *shared* (shared this)
                        *parent* this]
                (apply render this args))
              (log/fatal "Cannot find app on component!"))))))]

  (defn configure-component!
    "Configure the given `cls` (a function) to act as a react component within the Fulcro ecosystem.

    cls - A js function (in clj, this is ignored)
    fqkw - A keyword that shares the exact fully-qualified name of the component class
    options - A component options map (no magic) containing things like `:query` and `:ident`.


    NOTE: the `options` map expects proper function signatures for:

    `:query` - (fn [this] ...)
    `:ident` - (fn [this props] ...)
    `:initial-state` - (fn [cls params] ...)

    Returns (and registers) a new react class.
    "
    [cls fqkw options]
    #?(:clj
       (let [name   (str/join "/" [(namespace fqkw) (name fqkw)])
             {:keys [render]} options
             result {::component-class?  true
                     :fulcro$options     (assoc options :render (wrap-base-render render))
                     :fulcro$registryKey fqkw
                     :displayName        name}]
         (register-component! fqkw result)
         result)
       :cljs
       ;; This user-supplied versions will expect `this` as first arg
       (let [{:keys [getDerivedStateFromProps shouldComponentUpdate getSnapshotBeforeUpdate render
                     initLocalState componentDidCatch getDerivedStateFromError
                     componentWillUpdate componentWillMount componentWillReceiveProps
                     UNSAFE_componentWillMount UNSAFE_componentWillUpdate UNSAFE_componentWillReceiveProps]} options
             name              (str/join "/" [(namespace fqkw) (name fqkw)])
             js-instance-props (clj->js
                                 (-> {:componentDidMount     component-did-mount
                                      :componentWillUnmount  component-will-unmount
                                      :componentDidUpdate    component-did-update
                                      :shouldComponentUpdate (if shouldComponentUpdate
                                                               (wrap-props-state-handler shouldComponentUpdate)
                                                               should-component-update?)
                                      :fulcro$isComponent    true
                                      :type                  cls
                                      :displayName           name}
                                   (cond->
                                     render (assoc :render (wrap-base-render render))
                                     getSnapshotBeforeUpdate (assoc :getSnapshotBeforeUpdate (wrap-props-state-handler getSnapshotBeforeUpdate))
                                     componentDidCatch (assoc :componentDidCatch (wrap-this componentDidCatch))
                                     UNSAFE_componentWillMount (assoc :UNSAFE_componentWillMount (wrap-this UNSAFE_componentWillMount))
                                     UNSAFE_componentWillUpdate (assoc :UNSAFE_componentWillUpdate (wrap-props-state-handler UNSAFE_componentWillUpdate))
                                     UNSAFE_componentWillReceiveProps (assoc :UNSAFE_componentWillReceiveProps (wrap-props-handler UNSAFE_componentWillReceiveProps))
                                     componentWillMount (assoc :componentWillMount (wrap-this componentWillMount))
                                     componentWillUpdate (assoc :componentWillUpdate (wrap-this componentWillUpdate))
                                     componentWillReceiveProps (assoc :componentWillReceiveProps (wrap-props-handler componentWillReceiveProps))
                                     initLocalState (assoc :initLocalState (wrap-this initLocalState)))))
             statics           (cond-> {:displayName            name
                                        :fulcro$class           cls
                                        :cljs$lang$type         true
                                        :cljs$lang$ctorStr      name
                                        :cljs$lang$ctorPrWriter (fn [_ writer _] (cljs.core/-write writer name))}
                                 getDerivedStateFromError (assoc :getDerivedStateFromError (fn [error]
                                                                                             (let [v (getDerivedStateFromError error)]
                                                                                               (if (coll? v)
                                                                                                 #js {"fulcro$state" v}
                                                                                                 v))))
                                 getDerivedStateFromProps (assoc :getDerivedStateFromProps (static-wrap-props-state-handler getDerivedStateFromProps)))]
         (gobj/extend (.-prototype cls) (.-prototype react/Component) js-instance-props
           #js {"fulcro$options" options})
         (gobj/extend cls (clj->js statics) #js {"fulcro$options" options})
         (gobj/set cls "fulcro$registryKey" fqkw)           ; done here instead of in extend (clj->js screws it up)
         (register-component! fqkw cls)))))

(defn add-hook-options!
  "Make a given `cls` (a plain fn) act like a a Fulcro component with the given component options map. Registers the
  new component in the component-registry. Component options MUST contain :componentName as be a fully-qualified
  keyword to name the component in the registry.

  component-options *must* include a unique `:componentName` (keyword) that will be used for registering the given
  function as the faux class in the component registry."
  [render-fn component-options]
  (rc/configure-anonymous-component! render-fn component-options))

(defn use-fulcro
  "Allows you to use a plain function as a Fulcro-managed React hooks component.

  * `js-props` - The React js props from the parent.
  * `faux-class` - A Fulcro faux class, which is a fn that has had `add-options!` called on it.

  Returns a cljs vector containing `this` and fulcro `props`. You should *not* use the returned `this` directly,
  as it is a placeholder.

  Prefer `defsc` or `configure-hooks-component! over using this directly.`
  "
  [js-props faux-class]
  #?(:cljs
     (let [app                     (isoget js-props :fulcro$app)
           tunnelled-props-state   (react/useState #js {})
           js-set-tunnelled-props! (aget tunnelled-props-state 1)
           {:keys [ident] :as options} (isoget faux-class :fulcro$options)
           faux-component-state    (react/useState (fn []
                                                     (when-not app
                                                       (log/error "Cannot create proper fulcro component, as *app* isn't bound."
                                                         "This happens when something renders a Fulcro component outside of Fulcro's render context."
                                                         "See `with-parent-context`."
                                                         "See https://book.fulcrologic.com/#err-comp-app-not-bound"))
                                                     (let [set-tunnelled-props! (fn [updater] (let [new-props (updater nil)] (js-set-tunnelled-props! new-props)))]
                                                       #js {:setState           set-tunnelled-props!
                                                            :fulcro$isComponent true
                                                            :fulcro$class       faux-class
                                                            :type               faux-class
                                                            :fulcro$options     options
                                                            :fulcro$mounted     false
                                                            :props              #js {:fulcro$app app}})))
           faux-component          (aget faux-component-state 0)
           current-state           (aget tunnelled-props-state 0 "fulcro$value")
           props                   (isoget js-props :fulcro$value)
           children                (isoget js-props :children)
           parent                  (isoget js-props :fulcro$parent)
           current-props           (newer-props props current-state)
           current-ident           (when ident (ident faux-class current-props))
           shared-props            (when app (shared app))]
       (doto (gobj/get faux-component "props")
         (gobj/set "fulcro$parent" parent)
         (gobj/set "fulcro$shared" shared-props)
         (gobj/set "fulcro$value" current-props)
         (gobj/set "children" children))
       (react/useEffect
         (fn []
           (let [original-ident   current-ident
                 index-component! (ah/app-algorithm app :index-component!)
                 drop-component!  (ah/app-algorithm app :drop-component!)]
             (gobj/set faux-component "fulcro$mounted" true)
             (index-component! faux-component)
             (fn []
               (gobj/set faux-component "fulcro$mounted" false)
               (drop-component! faux-component original-ident))))
         #?(:cljs #js [(second current-ident)]))
       [faux-component current-props])))

(defn mounted?
  "Returns true if the given component instance is mounted on the DOM."
  [this]
  #?(:clj  false
     :cljs (gobj/get this "fulcro$mounted" false)))

(defn set-state!
  "Set React component-local state.  The `new-state` is actually merged with the existing state (as per React docs),
  but is wrapped so that cljs maps are used (instead of js objs).  `callback` is an optional callback that will be
  called as per the React docs on setState."
  ([component new-state callback]
   #?(:clj
      (when-let [state-atom (:state component)]
        (swap! state-atom update merge new-state)
        (callback))
      :cljs
      (if (mounted? component)
        (.setState ^js component
          (fn [prev-state props]
            #js {"fulcro$state" (merge (gobj/get prev-state "fulcro$state") new-state)})
          callback))))
  ([component new-state]
   (set-state! component new-state nil)))

(defn get-state
  "Get a component's local state. May provide a single key or a sequential
   collection of keys for indexed access into the component's local state. NOTE: This is Fulcro's wrapped component
   local state. The low-level React state is as described in the React docs (e.g. `(.-state this)`)."
  ([component]
   (get-state component []))
  ([component k-or-ks]
   (let [cst #?(:clj (some-> component :state deref)
                :cljs (gobj/getValueByKeys component "state" "fulcro$state"))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(let [update-fn (fn [component f args]
                  #?(:cljs (.setState ^js component
                             (fn [prev-state props]
                               #js {"fulcro$state" (apply f (gobj/get prev-state "fulcro$state") args)}))))]
  (defn update-state!
    "Update a component's local state. Similar to Clojure(Script)'s swap!

    This function affects a managed cljs map maintained in React state.  If you want to affect the low-level
    js state itself use React's own `.setState` directly on the component."
    ([component f]
     (update-fn component f []))
    ([component f & args]
     (update-fn component f args))))

(def get-initial-state
  "
  [cls] [cls params]

  Get the declared :initial-state value for a component."
  rc/get-initial-state)

(defn computed-initial-state?
  "Returns true if the given initial state was returned from a call to get-initial-state. This is used by internal
  algorithms when interpreting initial state shorthand in `defsc`."
  [s]
  (and (map? s) (some-> s meta :computed)))

(def get-ident
  "
  [x] [class props]

  Get the ident for a mounted component OR using a component class.

  That arity-2 will return the ident using the supplied props map.

  The single-arity version should only be used with a mounted component (e.g. `this` from `render`), and will derive the
  props that were sent to it most recently."
  rc/get-ident)

(defn tunnel-props!
  "CLJS-only.  When the `component` is mounted this will tunnel `new-props` to that component through React `setState`. If you're in
  an event handler, this means the tunnelling will be synchronous, and can be useful when updating props that could affect DOM
  inputs. This is typically used internally (see `transact!!`, and should generally not be used in applications unless it is a very advanced
  scenario and you've studied how this works. NOTE: You should `tick!` the application clock and bind *denormalize-time*
  when generating `new-props` so they are properly time-stamped by `db->tree`, or manually add time to `new-props`
  using `fdn/with-time` directly."
  [component new-props]
  #?(:cljs
     (when (mounted? component)
       (.setState ^js component (fn [s] #js {"fulcro$value" new-props})))))

(defn is-factory?
  "Returns true if the given argument is a component factory."
  [class-or-factory]
  (rc/is-factory? class-or-factory))

(def query-id
  "[class qualifier]

   Returns a string ID for the query of the given class with qualifier."
  rc/query-id)

(def denormalize-query rc/denormalize-query)
(def get-query-by-id rc/get-query-by-id)

(defn get-query
  "Get the query for the given class or factory. If called without a state map, then you'll get the declared static
  query of the class. If a state map is supplied, then the dynamically set queries in that state will result in
  the current dynamically-set query according to that state."
  ([class-or-factory]
   (rc/get-query class-or-factory (or rc/*query-state*
                                    (some-> *app* :com.fulcrologic.fulcro.application/state-atom deref) {})))
  ([class-or-factory state-map]
   (rc/get-query class-or-factory state-map)))

(defn make-state-map
  "Build a component's initial state using the defsc initial-state-data from
  options, the children from options, and the params from the invocation of get-initial-state."
  [initial-state children-by-query-key params]
  (let [join-keys (set (keys children-by-query-key))
        init-keys (set (keys initial-state))
        is-child? (fn [k] (contains? join-keys k))
        value-of  (fn value-of* [[isk isv]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name isv)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key isk)]
                      (cond
                        ; parameterized lookup with no value
                        (and param-key (not param-exists?)) nil

                        ; to-one join, where initial state is a map to be used as child initial state *parameters* (enforced by defsc macro)
                        ; and which may *contain* parameters
                        (and (map? isv) (is-child? isk)) [isk (get-initial-state child-class (into {} (keep value-of* isv)))]

                        ; not a join. Map is literal initial value.
                        (map? isv) [isk (into {} (keep value-of* isv))]

                        ; to-many join. elements MUST be parameters (enforced by defsc macro)
                        (and (vector? isv) (is-child? isk)) [isk (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) isv)]

                        ; to-many join. elements might be parameter maps or already-obtained initial-state
                        (and (vector? param-value) (is-child? isk)) [isk (mapv (fn [params]
                                                                                 (if (computed-initial-state? params)
                                                                                   params
                                                                                   (get-initial-state child-class params))) param-value)]

                        ; vector of non-children
                        (vector? isv) [isk (mapv (fn [ele] (substitute ele)) isv)]

                        ; to-one join with parameter. value might be params, or an already-obtained initial-state
                        (and param-key (is-child? isk) param-exists?) [isk (if (computed-initial-state? param-value)
                                                                             param-value
                                                                             (get-initial-state child-class param-value))]
                        param-key [isk param-value]
                        :else [isk isv])))]
    (into {} (keep value-of initial-state))))

(defn wrapped-render
  "Run `real-render`, possibly through :render-middleware configured on your app."
  [this real-render]
  #?(:clj
     (real-render)
     :cljs
     (let [app               (gobj/getValueByKeys this "props" "fulcro$app")
           render-middleware (ah/app-algorithm app :render-middleware)]
       (binding [*app*    app
                 *parent* this
                 *shared* (shared app)]
         (if render-middleware
           (render-middleware this real-render)
           (real-render))))))

(defn configure-hooks-component!
  "Configure a function `(f [this fulcro-props] ...)` to work properly as a hook-based react component. This can be
  used in leiu of `defsc` to create a component, where `options` is the (non-magic) map of component options
  (i.e. :query is a `(fn [this])`, not a vector).

  IMPORTANT: Your options must include `:componentName`, a fully-qualified keyword to use in the component registry.

  Returns a new function that wraps yours (to properly extract Fulcro props) and installs the proper Fulcro component
  options on the low-level function so that it will act properly when used within React as a hook-based component.

  (def MyComponent
    (configure-hooks-component!
      (fn [this props]
        (let [[v set-v!] (use-state this 0)
          (dom/div ...)))
      {:query ... :ident (fn [_ props] ...) :componentName ::MyComponent}))

  (def ui-my-component (comp/factory MyComponent {:keyfn :id})

  This can be used to easily generate dynamic components at runtime (as can `configure-component!`).
  "
  [f options]
  (let [cls-atom (atom nil)
        js-fn    (fn [js-props]
                   (let [[this props] (use-fulcro js-props @cls-atom)]
                     (wrapped-render this (fn [] (f this props)))))]
    (reset! cls-atom js-fn)
    (add-hook-options! js-fn options)))

(defn- create-element
  "Create a react element for a Fulcro class.  In CLJ this returns the same thing as a mounted instance, whereas in CLJS it is an
  element (which has yet to instantiate an instance)."
  [class props children]
  #?(:clj
     (let [init-state (component-options class :initLocalState)
           state-atom (atom {})
           this       {::element?          true
                       :fulcro$isComponent true
                       :props              props
                       :children           children
                       :state              state-atom
                       :fulcro$class       class}
           state      (when init-state (init-state this))]
       (when (map? state)
         (reset! state-atom state))
       this)
     :cljs
     (apply react/createElement class props (force-children children))))

(defn factory
  "Create a factory constructor from a component class created with
   defsc."
  ([class] (factory class nil))
  ([class {:keys [keyfn qualifier] :as opts}]
   (let [qid (query-id class qualifier)]
     (with-meta
       (fn element-factory [props & children]
         (let [key    (:react-key props)
               key    (cond
                        key key
                        keyfn (keyfn props))
               parent *parent*
               app    #?(:cljs *app* :clj (or *app* {}))]
           (if app
             (let [ref              (:ref props)
                   ref              (cond-> ref (keyword? ref) str)
                   props-middleware (some-> app (ah/app-algorithm :props-middleware))
                   ;; Our data-readers.clj makes #js == identity in CLJ
                   props            #js {:fulcro$value   props
                                         :fulcro$queryid qid
                                         :fulcro$parent  parent
                                         :fulcro$app     app}
                   props            (if props-middleware
                                      (props-middleware class props)
                                      props)]
               #?(:cljs
                  (do
                    (when key
                      (gobj/set props "key" key))
                    (when ref
                      (gobj/set props "ref" ref))
                    ;; dev time warnings/errors
                    (when goog.DEBUG
                      (when (or (map? key) (vector? key))
                        (log/warn "React key for " (component-name class) " is not a simple scalar value. This could cause spurious component remounts. See https://book.fulcrologic.com/#warn-react-key-not-simple-scalar"))

                      (when (string? ref)
                        (log/warn "String ref on " (component-name class) " should be a function. See https://book.fulcrologic.com/#warn-string-ref-not-function"))

                      (when (or (nil? props) (not (gobj/containsKey props "fulcro$value")))
                        (log/error "Props middleware seems to have corrupted props for " (component-name class) "See https://book.fulcrologic.com/#err-comp-props-middleware-corrupts"))

                      (when-not ((fnil map? {}) (gobj/get props "fulcro$value"))
                        (log/error "Props passed to" (component-name class) "are of the type"
                          (type->str (type (gobj/get props "fulcro$value")))
                          "instead of a map. Perhaps you meant to `map` the component over the props? See https://book.fulcrologic.com/#err-comp-props-not-a-map")))))
               (create-element class props children))
             (do
               (log/error
                 "A Fulcro component was rendered outside of a parent context. This probably means you are using a library that has you pass rendering code to it as a lambda. Use `with-parent-context` to fix this. See https://book.fulcrologic.com/#err-comp-rendered-outside-parent-ctx")
               []))))
       {:class     class
        :queryid   qid
        :qualifier qualifier}))))

(defn computed-factory
  "Similar to factory, but returns a function with the signature
  [props computed & children] instead of default [props & children].
  This makes easier to send computed."
  ([class] (computed-factory class {}))
  ([class options]
   (let [real-factory (factory class options)]
     (with-meta
       (fn
         ([props] (real-factory props))
         ([props computed-props]
          (real-factory (computed props computed-props)))
         ([props computed-props & children]
          (apply real-factory (computed props computed-props) children)))
       (meta real-factory)))))

(defn transact!
  "Submit a transaction for processing.

  The underlying transaction system is pluggable, but the *default* supported options are:

  - `:optimistic?` - boolean. Should the transaction be processed optimistically?
  - `:ref` - ident. The ident of the component used to submit this transaction. This is set automatically if you use a component to call this function.
  - `:component` - React element. Set automatically if you call this function using a component.
  - `:refresh` - Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - Vector of idents/keywords.  If the underlying rendering configured algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI.
    This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.
  - `:abort-id` - An ID (you make up) that makes it possible (if the plugins you're using support it) to cancel
    the network portion of the transaction (assuming it has not already completed).
  - `:compressible?` - boolean. Check compressible-transact! docs.
  - `:synchronous?` - boolean. When turned on the transaction will run immediately on the calling thread. If run against
  a component then the props will be immediately tunneled back to the calling component, allowing for React (raw) input
  event handlers to behave as described in standard React Forms docs (uses setState behind the scenes). Any remote operations
  will still be queued as normal. Calling `transact!!` is a shorthand for this option. WARNING: ONLY the given component will
  be refreshed in the UI. If you have dependent data elsewhere in the UI you must either use `transact!` or schedule
  your own global render using `app/schedule-render!`.
  - `:after-render?` - Wait until the next render completes before allowing this transaction to run. This can be used
  when calling `transact!` from *within* another mutation to ensure that the effects of the current mutation finish
  before this transaction takes control of the CPU. This option defaults to `false`, but `defmutation` causes it to
  be set to true for any transactions run within mutation action sections. You can affect the default for this value
  in a dynamic scope by binding `rc/*after-render*` to true
  - `:parallel?` - Boolean. If true, the mutation(s) in the transaction will NOT go into a network queue, nor
    will it block later mutations or queries.

  You may add any additional keys to the option map (namespaced is ideal), and any value is legal in the options
  map, including functions. The options will appear in the `env` of all mutations run in the transaction as
  `:com.fulcrologic.fulcro.algorithms.tx-processing/options`. This is the preferred way of passing things like
  lambdas (if you wanted something like a callback) to mutations. Note that mutation symbols are perfectly legal
  as mutation *arguments*, so chaining mutations can already be done via the normal transaction mechanism, and
  callbacks, while sometimes necessary/useful, should be limited to usages where there is no other clean way
  to accomplish the goal.

  NOTE: This function calls the application's `tx!` function (which is configurable). Fulcro 2 'follow-on reads' are
  supported by the default version and are added to the `:refresh` entries. Your choice of rendering algorithm will
  influence their necessity.

  Returns the transaction ID of the submitted transaction.
  "
  ([app-or-component tx options] (rc/transact! app-or-component tx options))
  ([app-or-comp tx] (rc/transact! app-or-comp tx {})))

(defn transact!!
  "Shorthand for exactly `(transact! component tx (merge options {:synchronous? true}))`.

  Runs a synchronous transaction, which is an optimized mode where the optimistic behaviors of the mutations in the
  transaction run on the calling thread, and new props are immediately made available to the calling component via
  \"props tunneling\" (a behind-the-scenes mechanism using js/setState).

  This mode is meant to be used in form input event handlers, since React is designed to only work properly with
  raw DOM inputs via component-local state. This prevents things like the cursor jumping to the end of inputs
  unexpectedly.

  WARNING: Using an `app` instead of a component in synchronous transactions makes no sense. You must pass a component
  that has an ident.

  If you're using this, you can also set the compiler option:

  ```
  :compiler-options {:external-config {:fulcro     {:wrap-inputs? false}}}
  ```

  to turn off Fulcro DOM's generation of wrapped inputs (which try to solve this problem in a less-effective way).

  WARNING: Synchronous rendering does *not* refresh the full UI, only the component.
  "
  ([component tx] (rc/transact!! component tx {}))
  ([component tx options]
   (rc/transact! component tx (merge options {:synchronous? true}))))

(declare normalize-query)

(def link-element "Part of internal implementation of dynamic queries." rc/link-element)

(def normalize-query-elements
  "Part of internal implementation of dynamic queries.

  Determines if there are query elements in the `query` that need to be normalized. If so, it does so.

  Returns the new state map containing potentially-updated normalized queries."
  rc/normalize-query-elements)

(def link-query
  "Part of dyn query implementation. Find all of the elements (only at the top level) of the given query and replace them
  with their query ID."
  rc/link-query)

(def normalize-query
  "Given a state map and a query, returns a state map with the query normalized into the database. Query fragments
  that already appear in the state will not be added.  Part of dynamic query implementation."
  rc/normalize-query)

(defn set-query*
  "Put a query in app state.

  NOTE: Indexes must be rebuilt after setting a query, so this function should primarily be used to build
  up an initial app state."
  [state-map class-or-factory {:keys [query] :as args}]
  (rc/set-query* state-map class-or-factory args))

(defn set-query!
  "Public API for setting a dynamic query on a component. This function alters the query and rebuilds internal indexes.

  * `x` : is anything that any->app accepts.
  * `class-or-factory` : A component class or factory for that class (if using query qualifiers)
  * `opts` : A map with `query` and optionally `params` (substitutions on queries)
  "
  [x class-or-factory {:keys [query params] :as opts}]
  (rc/set-query! x class-or-factory opts))

(defn refresh-dynamic-queries!
  "Refresh the current dynamic queries in app state to reflect any updates to the static queries of the components.

   This can be used at development time to update queries that have changed but that hot code reload does not
   reflect (because there is a current saved query in state). This is *not* always what you want, since a component
   may have a custom query whose prop-level elements are set to a particular thing on purpose.

   An component that has `:preserve-dynamic-query? true` in its component options will be ignored by
   this function."
  ([app-ish cls force?] (rc/refresh-dynamic-queries! app-ish cls force?))
  ([app-ish] (rc/refresh-dynamic-queries! app-ish)))

(defn get-indexes
  "Get all of the indexes from a component instance or app. See also `ident->any`, `class->any`, etc."
  [x]
  (let [app (any->app x)]
    (some-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/indexes)))

(defn ident->components
  "Return all on-screen component instances that are rendering the data for a given ident. `x` is anything any->app accepts."
  [x ident]
  (some-> (get-indexes x) :ident->components (get ident)))

(defn ident->any
  "Return some (random) on-screen components that uses the given ident. `x` is anything any->app accepts."
  [x ident]
  (first (ident->components x ident)))

(defn prop->classes
  "Get all component classes that query for the given prop.
  `x` can be anything `any->app` is ok with.

  Returns all classes that query for that prop (or ident)"
  [x prop]
  (some-> (get-indexes x) :prop->classes (get prop)))

(defn class->all
  "Get all of the on-screen component instances from the indexes that have the type of the component class.
  `x` can be anything `any->app` is ok with."
  [x class]
  (let [k (class->registry-key class)]
    (some-> (get-indexes x) :class->components (get k))))

(defn class->any
  "Get a (random) on-screen component instance from the indexes that has type of the given component class.
  `x` can be anything `any->app` is ok with."
  [x cls]
  (first (class->all x cls)))

(defn component->state-map
  "Returns the current value of the state map via a component instance. Note that it is not safe to render
  arbitrary data from the state map since Fulcro will have no idea that it should refresh a component that
  does so; however, it is sometimes useful to look at the state map for information that doesn't
  change over time."
  [this] (some-> this any->app :com.fulcrologic.fulcro.application/state-atom deref))

(defn wrap-update-extra-props
  "Wrap the props middleware such that `f` is called to get extra props that should be placed
  in the extra-props arg of the component.

  `handler` - (optional) The next item in the props middleware chain.
  `f` - A (fn [cls extra-props] new-extra-props)

  `f` will be passed the class being rendered and the current map of extra props. It should augment
  those and return a new version."
  ([f]
   (fn [cls raw-props]
     #?(:clj  (update raw-props :fulcro$extra_props (partial f cls))
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)
                raw-props))))
  ([handler f]
   (fn [cls raw-props]
     #?(:clj  (let [props (update raw-props :fulcro$extra_props (partial f cls))]
                (handler cls props))
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)
                (handler cls raw-props))))))

(defn fragment
  "Wraps children in a React.Fragment. Props are optional, like normal DOM elements."
  [& args]
  #?(:clj
     (let [optional-props (first args)
           props?         (and (instance? APersistentMap optional-props) (not (component-instance? optional-props)))
           [_ children] (if props?
                          [(first args) (rest args)]
                          [{} args])]
       (vec children))
     :cljs
     (let [[props children] (if (map? (first args))
                              [(first args) (rest args)]
                              [#js {} args])]
       (apply react/createElement react/Fragment (clj->js props) (force-children children)))))

#?(:clj
   (defmacro with-parent-context
     "Wraps the given body with the correct internal bindings of the parent so that Fulcro internals
     will work when that body is embedded in unusual ways (e.g. as the body in a child-as-a-function
     React pattern).

     ```
     (defsc X [this props]
       ...
       ;; WRONG:
       (some-react-thing {:child (fn [] (ui-fulcro-thing ...))})
       ;; CORRECT:
       (some-react-thing {:child (fn [] (with-parent-context this (ui-fulcro-thing ...)))})
     ```
     "
     [outer-parent & body]
     (if-not (:ns &env)
       `(do ~@body)
       `(let [parent# ~outer-parent
              app#    (or *app* (any->app parent#))
              s#      (shared app#)
              p#      (or *parent* parent#)]
          (binding [*app*    app#
                    *shared* s#
                    *parent* p#]
            ~@body)))))

(defn ptransact!
  "
  DEPRECATED: Generally use `result-action` in mutations to chain sequences instead. This call is equivalent
  to `transact!` with an `:optimistic? false` option.

  Like `transact!`, but ensures each call completes (in a full-stack, pessimistic manner) before the next call starts
  in any way. Note that two calls of this function have no guaranteed relationship to each other. They could end up
  intermingled at runtime. The only guarantee is that for *a single call* to `ptransact!`, the calls in the given tx will run
  pessimistically (one at a time) in the order given. Follow-on reads in the given transaction will be repeated after each remote
  interaction.

  `component-or-app` a mounted component or the app
  `tx` the tx to run
  `ref` the ident (ref context) in which to run the transaction (including all deferrals)"
  ([component-or-app tx]
   (transact! component-or-app tx {:optimistic? false}))
  ([component-or-app ref tx]
   (transact! component-or-app tx {:optimistic? false
                                   :ref         ref})))

(defn compressible-transact!
  "Identical to `transact!` with `:compressible? true` option. This means that if more than one
  adjacent history transition edge is compressible, only the more recent of the sequence of them is kept. This
  is useful for things like form input fields, where storing every keystoke in history is undesirable. This
  also compress the transactions in Fulcro Inspect.

  NOTE: history events that trigger remote interactions are not compressible, since they may be needed for
  automatic network error recovery handling."
  ([app-ish tx]
   (rc/transact! app-ish tx {:compressible? true}))
  ([app-ish ref tx]
   (rc/transact! app-ish tx {:compressible? true
                             :ref           ref})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFSC MACRO SUPPORT. Most of this could be in a diff ns, but then hot code reload while working on the macro
;; does not work right.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cljs?
     "A CLJ macro helper. `env` is the macro's `&env` value. Returns true when expanding a macro while compiling CLJS."
     [env]
     (boolean (:ns env))))

#?(:clj
   (defn- is-link?
     "Returns true if the given query element is a link query like [:x '_]."
     [query-element] (and (vector? query-element)
                       (keyword? (first query-element))
                       ; need the double-quote because when in a macro we'll get the literal quote.
                       (#{''_ '_} (second query-element)))))

#?(:clj
   (defn -legal-keys
     "PRIVATE. Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
     use the AST)"
     [query]
     (letfn [(keeper [ele]
               (cond
                 (list? ele) (recur (first ele))
                 (keyword? ele) ele
                 (is-link? ele) (first ele)
                 (and (map? ele) (keyword? (ffirst ele))) (ffirst ele)
                 (and (map? ele) (is-link? (ffirst ele))) (first (ffirst ele))
                 :else nil))]
       (set (keep keeper query)))))

#?(:clj
   (defn- children-by-prop
     "Part of Defsc macro implementation. Calculates a map from join key to class (symbol)."
     [query]
     (into {}
       (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
                (let [k   (if (vector? (ffirst %))
                            (first (ffirst %))
                            (ffirst %))
                      cls (-> % first second second)]
                  [k cls])
                nil) query))))

#?(:clj
   (defn- replace-and-validate-fn
     "Replace the first sym in a list (the function name) with the given symbol.

     env - the macro &env
     sym - The symbol that the lambda should have
     external-args - A sequence of arguments that the user should not include, but that you want to be inserted in the external-args by this function.
     user-arity - The number of external-args the user should supply (resulting user-arity is (count external-args) + user-arity).
     fn-form - The form to rewrite
     sym - The symbol to report in the error message (in case the rewrite uses a different target that the user knows)."
     ([env sym external-args user-arity fn-form] (replace-and-validate-fn env sym external-args user-arity fn-form sym))
     ([env sym external-args user-arity fn-form user-known-sym]
      (when-not (<= user-arity (count (second fn-form)))
        (throw (ana/error (merge env (meta fn-form)) (str "Invalid arity for " user-known-sym ". Expected " user-arity " or more."))))
      (let [user-args    (second fn-form)
            updated-args (into (vec (or external-args [])) user-args)
            body-forms   (drop 2 fn-form)]
        (->> body-forms
          (cons updated-args)
          (cons sym)
          (cons 'fn))))))

#?(:clj
   (defn- component-query [query-part]
     (and (list? query-part)
       (symbol? (first query-part))
       (= "get-query" (name (first query-part)))
       query-part)))

#?(:clj
   (defn- compile-time-query->checkable
     "Try to simplify the compile-time query (as seen by the macro)
     to something that EQL can check (`(get-query ..)` => a made-up vector).
     Returns nil if this is not possible."
     [query]
     (try
       (prewalk
         (fn [form]
           (cond
             (component-query form)
             [(keyword (str "subquery-of-" (some-> form second name)))]

             ;; Replace idents with idents that contain only keywords, so syms don't trip us up
             (and (vector? form) (= 2 (count form)))
             (mapv #(if (symbol? %) :placeholder %) form)

             (symbol? form)
             (throw (ex-info "Cannot proceed, the query contains a symbol" {:sym form}))

             :else
             form))
         query)
       (catch Throwable _
         nil))))

#?(:clj
   (defn- check-query-looks-valid [err-env comp-class compile-time-query]
     (let [checkable-query (compile-time-query->checkable compile-time-query)]
       (when (false? (some->> checkable-query (s/valid? ::eql/query)))
         (let [{:clojure.spec.alpha/keys [problems]} (s/explain-data ::eql/query checkable-query)
               {:keys [in]} (first problems)]
           (when (vector? in)
             (throw (ana/error err-env (str "The element '" (get-in compile-time-query in) "' of the query of " comp-class " is not valid EQL")))))))))

#?(:clj
   (defn- build-query-forms
     "Validate that the property destructuring and query make sense with each other."
     [env class thissym propargs {:keys [template method]}]
     (cond
       template
       (do
         (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
         (let [to-keyword            (fn [s] (cond
                                               (nil? s) nil
                                               (keyword? s) s
                                               :otherwise (let [nspc (namespace s)
                                                                nm   (name s)]
                                                            (keyword nspc nm))))
               destructured-keywords (when (map? propargs) (util/destructured-keys propargs))
               queried-keywords      (-legal-keys template)
               has-wildcard?         (some #{'*} template)
               to-sym                (fn [k] (symbol (namespace k) (name k)))
               illegal-syms          (mapv to-sym (set/difference destructured-keywords queried-keywords))
               err-env               (merge env (meta template))]
           (when-let [child-query (some component-query template)]
             (throw (ana/error err-env (str "defsc " class ": `get-query` calls in :query can only be inside a join value, i.e. `{:some/key " child-query "}`"))))
           (when (and (not has-wildcard?) (seq illegal-syms))
             (throw (ana/error err-env (str "defsc " class ": " illegal-syms " was destructured in props, but does not appear in the :query!"))))
           `(~'fn ~'query* [~thissym] ~template)))
       method
       (replace-and-validate-fn env 'query* [thissym] 0 method))))

#?(:clj
   (defn- build-ident
     "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
     in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
     entry in defsc without error checking."
     [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
     (cond
       keyword (if (is-legal-key? keyword)
                 `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
                 (throw (ana/error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
       method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
       template (let [table   (first template)
                      id-prop (or (second template) :db/id)]
                  (cond
                    (nil? table) (throw (ana/error (merge env (meta template)) "TABLE part of ident template was nil" {}))
                    (not (is-legal-key? id-prop)) (throw (ana/error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                    :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)]))))))

#?(:clj
   (defn- build-render [classsym thissym propsym compsym extended-args-sym body]
     (let [computed-bindings (when compsym `[~compsym (com.fulcrologic.fulcro.components/get-computed ~thissym)])
           extended-bindings (when extended-args-sym `[~extended-args-sym (com.fulcrologic.fulcro.components/get-extra-props ~thissym)])
           render-fn         (symbol (str "render-" (name classsym)))]
       `(~'fn ~render-fn [~thissym]
          (com.fulcrologic.fulcro.components/wrapped-render ~thissym
            (fn []
              (let [~propsym (com.fulcrologic.fulcro.components/props ~thissym)
                    ~@computed-bindings
                    ~@extended-bindings]
                ~@body)))))))

#?(:clj
   (defn- build-hooks-render [classsym thissym propsym compsym extended-args-sym body]
     (let [computed-bindings (when compsym `[~compsym (com.fulcrologic.fulcro.components/get-computed ~thissym)])
           extended-bindings (when extended-args-sym `[~extended-args-sym (com.fulcrologic.fulcro.components/get-extra-props ~thissym)])
           render-fn         (symbol (str "render-" (name classsym)))]
       `(~'fn ~render-fn [~thissym ~propsym]
          (com.fulcrologic.fulcro.components/wrapped-render ~thissym
            (fn []
              (binding [*app*    (or *app* (isoget-in ~thissym ["props" "fulcro$app"]))
                        *shared* (shared (or *app* (isoget-in ~thissym ["props" "fulcro$app"])))
                        *parent* ~thissym]
                (let [~@computed-bindings
                      ~@extended-bindings]
                  ~@body))))))))

#?(:clj
   (defn- build-and-validate-initial-state-map [env sym initial-state legal-keys children-by-query-key]
     (let [env           (merge env (meta initial-state))
           join-keys     (set (keys children-by-query-key))
           init-keys     (set (keys initial-state))
           illegal-keys  (if (set? legal-keys) (set/difference init-keys legal-keys) #{})
           is-child?     (fn [k] (contains? join-keys k))
           param-expr    (fn [v]
                           (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                         (keyword (name v)))]
                             `(~kw ~'params)
                             v))
           parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
           child-state   (fn [k]
                           (let [state-params    (get initial-state k)
                                 to-one?         (map? state-params)
                                 to-many?        (and (vector? state-params) (every? map? state-params))
                                 code?           (list? state-params)
                                 from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                                 child-class     (get children-by-query-key k)]
                             (when code?
                               (throw (ana/error env (str "defsc " sym ": Illegal parameters to :initial-state " state-params ". Use a lambda if you want to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide."))))
                             (cond
                               (not (or from-parameter? to-many? to-one?)) (throw (ana/error env (str "Initial value for a child (" k ") must be a map or vector of maps!")))
                               to-one? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized state-params))
                               to-many? (mapv (fn [params]
                                                `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized params)))
                                          state-params)
                               from-parameter? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(param-expr state-params))
                               :otherwise nil)))
           kv-pairs      (map (fn [k]
                                [k (if (is-child? k)
                                     (child-state k)
                                     (param-expr (get initial-state k)))]) init-keys)
           state-map     (into {} kv-pairs)]
       (when (seq illegal-keys)
         (throw (ana/error env (str "Initial state includes keys " illegal-keys ", but they are not in your query."))))
       `(~'fn ~'build-initial-state* [~'params] (com.fulcrologic.fulcro.components/make-state-map ~initial-state ~children-by-query-key ~'params)))))

#?(:clj
   (defn- build-raw-initial-state
     "Given an initial state form that is a list (function-form), simple copy it into the form needed by defsc."
     [env method]
     (replace-and-validate-fn env 'build-raw-initial-state* [] 1 method)))

#?(:clj
   (defn- build-initial-state [env sym {:keys [template method]} legal-keys query-template-or-method]
     (when (and template (contains? query-template-or-method :method))
       (throw (ana/error (merge env (meta template)) (str "When query is a method, initial state MUST be as well."))))
     (cond
       method (build-raw-initial-state env method)
       template (let [query    (:template query-template-or-method)
                      children (or (children-by-prop query) {})]
                  (build-and-validate-initial-state-map env sym template legal-keys children)))))

#?(:clj
   (s/def ::ident (s/or :template (s/and vector? #(= 2 (count %))) :method list? :keyword keyword?)))
#?(:clj
   ;; NOTE: We cannot reuse ::eql/query because we have the raw input *form* inside a macro,
   ;; not the actual *data* that will be there at runtime (i.e. it may contain raw fn calls etc.)
   (s/def ::query (s/or :template vector? :method list?)))
#?(:clj
   (s/def ::initial-state (s/or :template map? :method list?)))
#?(:clj
   (s/def ::options (s/keys :opt-un [::query
                                     ::ident
                                     ::initial-state])))

#?(:clj
   (s/def ::args (s/cat
                   :sym symbol?
                   :doc (s/? string?)
                   :arglist (s/and vector? #(<= 2 (count %) 5))
                   :options (s/? map?)
                   :body (s/* any?))))

(defn react-constructor
  "Mainly for internal use and for use with `configure-component!`. Returns a constructor function which is usable
   as the React Class for `configure-component!`. E.g.:

   ```
   (defonce MyClass (react-constructor (fn [this initial-props] fulcro-component-local-state)))
   (configure-component! MyClass ::MyClass {:query ...})
   ```

   The argument is exactly the lambda you would normally pass to `initLocalState`.

   This function returns a React-compatible constructor (a function that receives initial `js-props` and
   sets up a js object as component local state). This is wrapped in Fulcro itself, thus this convenience function.
   "
  [init-state]
  #?(:clj  nil
     :cljs (fn [js-props]
             (this-as this
               (if init-state
                 (set! (.-state this) #js {"fulcro$state" (init-state this (isoget js-props "fulcro$value"))})
                 (set! (.-state this) #js {"fulcro$state" {}}))
               nil))))

#?(:clj
   (defn defsc*
     [env args]
     (when-not (s/valid? ::args args)
       (throw (ana/error env (str "Invalid arguments. " (-> (s/explain-data ::args args)
                                                          ::s/problems
                                                          first
                                                          :path) " is invalid."))))
     (let [{:keys [sym doc arglist options body]} (s/conform ::args args)
           [thissym propsym computedsym extra-args] arglist
           _                                (when (and options (not (s/valid? ::options options)))
                                              (let [path    (-> (s/explain-data ::options options) ::s/problems first :path)
                                                    message (cond
                                                              (= path [:query :template]) "The query template only supports vectors as queries. Unions or expression require the lambda form."
                                                              (= :ident (first path)) "The ident must be a keyword, 2-vector, or lambda of no arguments."
                                                              :else "Invalid component options. Please check to make\nsure your query, ident, and initial state are correct.")]
                                                (throw (ana/error env message))))
           {:keys [ident query initial-state]} (s/conform ::options options)
           body                             (or body ['nil])
           ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
           initial-state-template-or-method (into {} [initial-state])
           query-template-or-method         (into {} [query])
           validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
           legal-key-checker                (if validate-query?
                                              (or (-legal-keys (:template query-template-or-method)) #{})
                                              (complement #{}))
           ident-form                       (build-ident env thissym propsym ident-template-or-method legal-key-checker)
           state-form                       (build-initial-state env sym initial-state-template-or-method legal-key-checker query-template-or-method)
           query-form                       (build-query-forms env sym thissym propsym query-template-or-method)
           _                                (when validate-query?
                                              ;; after build-query-forms as it also does some useful checks
                                              (check-query-looks-valid env sym (:template query-template-or-method)))
           hooks?                           (and (cljs? env) (:use-hooks? options))
           render-form                      (if hooks?
                                              (build-hooks-render sym thissym propsym computedsym extra-args body)
                                              (build-render sym thissym propsym computedsym extra-args body))
           nspc                             (if (cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw                             (keyword (str nspc) (name sym))
           options-map                      (cond-> options
                                              state-form (assoc :initial-state state-form)
                                              ident-form (assoc :ident ident-form)
                                              query-form (assoc :query query-form)
                                              hooks? (assoc :componentName fqkw)
                                              render-form (assoc :render render-form))]
       (cond
         hooks?
         `(do
            (defonce ~sym
              (fn [js-props#]
                (let [render# (:render (component-options ~sym))
                      [this# props#] (use-fulcro js-props# ~sym)]
                  (render# this# props#))))
            (add-hook-options! ~sym ~options-map))

         (cljs? env)
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (defonce ~(vary-meta sym assoc :doc doc :jsdoc ["@constructor"])
                (react-constructor (get options# :initLocalState)))
              (com.fulcrologic.fulcro.components/configure-component! ~sym ~fqkw options#)))

         :else
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (def ~(vary-meta sym assoc :doc doc :once true)
                (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~fqkw options#))))))))

(let [hot-reload-cache (volatile! {})]
  (defn sc
    "Create a Fulcro stateful component. Calling this will overwrite whatever is currently in the registry. You MAY
     supply shortcut notation for query and ident.

     :query - A vector, map (for unions), or (fn [this]).
     :ident - A keyword, vector, or (fn [this props])
     :initial-state - A (fn [params] )

     The `render-fn` is a `(fn [this props])`.
    "
    [registry-key {:keys [use-hooks? query ident] :as component-options} render-fn]
    (assert (qualified-keyword? registry-key) "The registry key must be a qualified keyword.")
    (let [render-fn (if use-hooks?
                      (fn render-fn* [this props]
                        (com.fulcrologic.fulcro.components/wrapped-render this
                          (fn []
                            (when render-fn
                              (binding [*app*    (or *app* (isoget-in this ["props" "fulcro$app"]))
                                        *shared* (shared (or *app* (isoget-in this ["props" "fulcro$app"])))
                                        *parent* this]
                                (render-fn this props))))))
                      (fn render-fn* [this]
                        (com.fulcrologic.fulcro.components/wrapped-render this
                          (fn [] (when render-fn
                                   (render-fn this (props this)))))))
          options   (-> component-options
                      (assoc :componentName registry-key :render render-fn)
                      (cond->
                        (not (fn? query)) (assoc :query (fn [_] query))
                        (keyword? ident) (assoc :ident (fn [_ p] [ident (get p ident)]))
                        (vector? ident) (assoc :ident (fn [_ p] [(first ident) (get p (second ident))]))))]
      #?(:clj
         (configure-component! nil registry-key options)
         :cljs
         (if use-hooks?
           (let [wrapped-hook (or (get @hot-reload-cache registry-key)
                                (fn wrapped-hook* [js-props]
                                  (let [[this props] (use-fulcro js-props wrapped-hook*)]
                                    (render-fn this props))))]
             (when goog.DEBUG
               (vswap! hot-reload-cache assoc registry-key wrapped-hook))
             (add-hook-options! wrapped-hook options)
             wrapped-hook)
           (let [constructor (or                            ; using `or` to short-circuit the call to react-constructor
                               (get @hot-reload-cache registry-key)
                               (react-constructor (get component-options :initLocalState)))]
             (when goog.DEBUG
               (vswap! hot-reload-cache assoc registry-key constructor))
             (configure-component! constructor registry-key options)))))))

#?(:clj
   (defmacro ^{:doc      "Define a stateful component. This macro emits a React UI class with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css, lifecycle methods,
   and a render method. It can also cause the class to implement additional protocols that you specify. Destructuring is
   supported in the argument list.

   The template (data-only) versions do not have any arguments in scope
   The lambda versions have arguments in scope that make sense for those lambdas, as listed below:

   ```
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed} extended-args]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; nothing is in scope
      ;; pre-merge, use a lamba to modify new merged data with component needs
      :pre-merge (fn [{:keys [data-tree current-normalized state-map query]}] (merge {:ui/default-value :start} data-tree))

      ; React Lifecycle Methods (for the default, class-based components)
      :initLocalState            (fn [this props] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
      :shouldComponentUpdate     (fn [this next-props next-state] ...)

      :componentDidUpdate        (fn [this prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
      :componentDidMount         (fn [this] ...)
      :componentWillUnmount      (fn [this] ...)

      ;; DEPRECATED IN REACT 16 (to be removed in 17):
      :componentWillReceiveProps        (fn [this next-props] ...)
      :componentWillUpdate              (fn [this next-props next-state] ...)
      :componentWillMount               (fn [this] ...)

      ;; Replacements for deprecated methods in React 16.3+
      :UNSAFE_componentWillReceiveProps (fn [this next-props] ...)
      :UNSAFE_componentWillUpdate       (fn [this next-props next-state] ...)
      :UNSAFE_componentWillMount        (fn [this] ...)

      ;; ADDED for React 16:
      :componentDidCatch         (fn [this error info] ...)
      :getSnapshotBeforeUpdate   (fn [this prevProps prevState] ...)

      ;; static.
      :getDerivedStateFromProps  (fn [props state] ...)

      ;; ADDED for React 16.6:
      ;; NOTE: The state returned from this function can either be:
      ;; a raw js map, where Fulcro's state is in a sub-key: `#js {\"fulcro$state\" {:fulcro :state}}`.
      ;; or a clj map. In either case this function will *overwrite* Fulcro's component-local state, which is
      ;; slighly different behavior than raw React (we have no `this`, so we cannot read Fulcro's state to merge it).
      :getDerivedStateFromError  (fn [error] ...)

      NOTE: shouldComponentUpdate should generally not be overridden other than to force it false so
      that other libraries can control the sub-dom. If you do want to implement it, then old props can
      be obtained from (prim/props this), and old state via (gobj/get (. this -state) \"fulcro$state\").

      ; React Hooks support
      ;; if true, creates a function-based instead of a class-based component, see the Developer's Guide for details
      :use-hooks? true

      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   NOTE: The options map is \"open\". That is: you can add whatever extra stuff you want to in order
   to co-locate data for component-related concerns. This is exactly what component-local css, the
   dynamic router, and form-state do.  The data that you add is available from `comp/component-options`
   on the component class and instances (i.e. `this`).

   See the Developer's Guide at book.fulcrologic.com for more details.
   "
               :arglists '([this dbprops computedprops]
                           [this dbprops computedprops extended-args])}
     defsc
     [& args]
     (try
       (defsc* &env args)
       (catch Exception e
         (if (contains? (ex-data e) :tag)
           (throw e)
           (throw (ana/error &env "Unexpected internal error while processing defsc. Please check your syntax." e)))))))

(def external-config rc/external-config)

(defn refresh-component!
  "Request that the given subtree starting a component be refreshed from the app database without re-rendering any parent. This
  is a synchronous call that will tunnel the props to the given component via an internal call to React setState."
  [component]
  (if (component? component)
    (let [prior-computed (or (get-computed component) {})
          {:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom]} (any->app component)
          state-map      @state-atom]
      (swap! runtime-atom update :com.fulcrologic.fulcro.application/basis-t inc)
      (let [ident    (get-ident component)
            query    (get-query component state-map)
            ui-props (computed (fdn/db->tree query (get-in state-map ident) state-map) prior-computed)]
        (tunnel-props! component ui-props)))
    (log/error "Cannot re-render a non-component. See https://book.fulcrologic.com/#err-comp-cannot-rerender-non-comp")))

(defn get-parent
  "Returns the nth parent of `this` (a React element). The optional `n` can be 0 (the immediate parent) or any positive
  integer. If this walks past root then this function returns nil."
  ([this n]
   (when-not (component-instance? this)
     (throw (ex-info "Cannot get parent. First argument is not a component instance." {:arg this})))
   (loop [element this
          level   n]
     (let [result (isoget-in element [:props :fulcro$parent])]
       (if (and result (pos-int? level))
         (recur result (dec level))
         result))))
  ([this]
   (get-parent this 0)))

(def check-component-registry!
  "Walks the complete list of components in the component registry and looks for problems. Used during dev mode to
   detect common problems that can cause runtime misbehavior."
  rc/check-component-registry!)

(def union-component?
  "[c] [c state-map]

   Returns true if c has a union query."
  rc/union-component?)

(def union-child-for-props
  "[instance]
   [cls-or-instance props]
   [cls-or-instance props state-map]

   Gets the child component class of the given Union component that should be used for the specific entity (props) supplied."
  rc/union-child-for-props)

(def query->component
  "[query]

   Return the component class that was used to generate a given query. e.g. `( = (query->component (get-query Component)) Component)`."
  rc/query->component)

(defn memo
  "Equivalent to React.memo, but compares Fulcro props instead of shallow js ones."
  [ComponentClass]
  #?(:cljs (letfn [(fulcro-props-equal? [old-props new-props]
                     (= (isoget old-props :fulcro$value) (isoget new-props :fulcro$value)))]
             (react/memo ComponentClass fulcro-props-equal?))
     :clj  ComponentClass))

(def registered-components
  "Returns a map from registry key (keyword) to the fulcro components that have registered. Registration is
   automatic for many of the component generation facilities (macros) as long as they were assigned a registry
   name."
  rc/registered-components)
