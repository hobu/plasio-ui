(ns plasio-ui.state
  (:require [om.core :as om]
            [cljs.reader :as reader]
            [plasio-ui.history :as history]
            [plasio-ui.util :as util]
            [plasio-ui.components :as components]
            [cljs.core.async :as async]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [plasio-ui.math :as math]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go]]))


(def ^:private default-init-state
  {:ui     {:open-panes   []
            :docked-panes []
            :locations {}
            :local-options {:flicker-fix false}}
   :resource-info {}
   :window {:width  0
            :height 0}
   :ro     {:circular?              false
            :point-size             2
            :point-size-attenuation 0.1
            :intensity-blend        0
            :intensity-clamps       [0 255]
            :color-ramp             :red-to-green
            :color-ramp-range       [0 1]
            :zrange                 [0 1]}
   :po     {:distance-hint            50
            :max-depth-reduction-hint 5}
   :pm     {:z-exaggeration 1}
   :current-actions {}
   :histogram {}
   :intensity-histogram {}
   :target-location {}
   :compass {}
   :comps  {}
   :clicked-point-info {}
   :available-resources {}
   :available-filters []
   :loaded-resources []
   :animation-settings {}
   :animation-runtime {}})

(defonce app-state (atom default-init-state))

(def root-state (om/root-cursor app-state))

(def root (om/ref-cursor root-state))
(def window (om/ref-cursor (:window root-state)))
(def resource-info (om/ref-cursor (:resource-info root-state)))
(def ui (om/ref-cursor (:ui root-state)))
(def ui-locations (om/ref-cursor (:locations ui)))
(def ui-local-options (om/ref-cursor (:local-options ui)))
(def ro (om/ref-cursor (:ro root-state)))
(def po (om/ref-cursor (:po root-state)))
(def pm (om/ref-cursor (:pm root-state)))
(def comps (om/ref-cursor (:comps root-state)))
(def histogram (om/ref-cursor (:histogram root-state)))
(def intensity-histogram (om/ref-cursor (:intensity-histogram root-state)))
(def current-actions (om/ref-cursor (:current-actions root-state)))
(def clicked-point-info (om/ref-cursor (:clicked-point-info root-state)))
(def target-location (om/ref-cursor (:target-location root-state)))
(def compass (om/ref-cursor (:compass root-state)))
(def available-resources (om/ref-cursor (:available-resources root-state)))
(def available-filters (om/ref-cursor (:available-filters root-state)))
(def loaded-resources (om/ref-cursor (:loaded-resources root-state)))

(def animation-settings (om/ref-cursor (:animation-settings root-state)))
(def animation-runtime (om/ref-cursor (:animation-runtime root-state)))


(def ^:const default-point-cloud-density-level 4)
(def ^:const point-cloud-density-levels
  {1 0.5
   2 0.4
   3 0.35
   4 0.3
   5 0.2
   6 0.1})

(defn reset-app-state! []
  (om/update! root default-init-state))


(defn toggle-pane! [id]
  (om/transact!
    ui
    (fn [ui]
      (let [op (-> ui :open-panes set)
            dp (-> ui :docked-panes set)]
        (if (or (op id) (dp id))
          (assoc ui
            :open-panes (vec (disj op id))
            :docked-panes (vec (disj dp id)))
          (assoc ui
            :open-panes (vec (conj op id))))))))

(defn dock-pane! [id]
  (om/transact!
    ui
    (fn [ui]
      (let [op (-> ui :open-panes set)
            dp (-> ui :docked-panes set)]
        (when (op id)
          (assoc ui :docked-panes (vec (conj dp id))
                    :open-panes (vec (disj op id))))))))

(defn undock-pane! [id]
  (om/transact!
    ui
    (fn [ui]
      (let [op (-> ui :open-panes set)
            dp (-> ui :docked-panes set)]
        (when (dp id)
          (assoc ui :docked-panes (vec (disj dp id))
                    :open-panes (vec (conj op id))))))))

(defn toggle-docker! []
  (om/transact! ui-local-options :docker-collapsed? not))


(defn toggle-timeline-widget! []
  (om/transact! ui-local-options :timeline-widget-collapsed? not))

(defn set-timeline-widget-visibility! [show?]
  (om/update! ui-local-options :timeline-widget-visible? show?))

(let [ls js/localStorage]
  (defn save-val! [key val]
    (let [v (pr-str val)]
      (.setItem js/localStorage (name key) v)))

  (defn get-val [key]
    (when-let [v (.getItem js/localStorage (name key))]
      (reader/read-string v))))


(defn set-ui-location! [id pos]
  (om/transact!
    ui-locations
    #(assoc % id pos)))

(defn save-local-state! [id state]
  (save-val! (str "local-app-state." id) state))

(defn load-local-state [id ]
  (get-val (str "local-app-state." id)))

(defn save-typed-address [val]
  (save-val! "saved-address" val))

(defn get-last-typed-address []
  (get-val "saved-address"))

(defn window-placement-seq []
  (iterate (fn [{l :left t :top}]
             {:left (+ l 20)
              :top  (+ t 20)})
           {:left 30 :top 50}))


(defn rearrange-panels []
  (om/transact! ui-locations
                (fn [_]
                  (into {}
                        (map (fn [id new-loc]
                               [id new-loc])
                             (:open-panes @ui)
                             (window-placement-seq))))))


(defn set-active-panel! [panel]
  (om/transact! ui-local-options #(assoc % :active-panel panel)))


(defn- js-camera-props [{:keys [azimuth distance max-distance target elevation]}]
  (js-obj
    "azimuth" azimuth
    "distance" distance
    "maxDistance" max-distance
    "target" (apply array target)
    "elevation" elevation))


(defn- camera-state [camera]
  (let [cam (.serialize camera)]
    {:azimuth      (aget cam "azimuth")
     :distance     (aget cam "distance")
     :max-distance (aget cam "maxDistance")
     :target       (into [] (aget cam "target"))
     :elevation    (aget cam "elevation")}))

(defn- ui-state [st]
  (select-keys st [:ro :po :pm :ui]))

(defn- params-state [st]
  (select-keys st [:server :resource]))

(defn current-state-snapshot []
  (let [camera (.-activeCamera (:mode-manager @comps))]
    (when camera
      (merge
        {:camera (camera-state camera)}
        (ui-state @root)
        (params-state @root)))))

(defn- save-current-snapshot!
  "Take a snapshot from the camera and save it"
  []
  (when-let [snapshot (current-state-snapshot)]
    (history/push-state snapshot)))

(defn- wrap-dismiss-context-menu [f]
  (fn [& args]
    (om/update! current-actions {})
    (apply f args)))

;; A simple way to throttle down changes to history, waits for 500ms
;; before applying a state, gives UI a chance to "settle down"
;;
(let [current-index (atom 0)]
  (defn do-save-current-snapshot []
    (go
      (let [index (swap! current-index inc)]
        (async/<! (async/timeout 500))
        (when (= index @current-index)
          ;; the index hasn't changed since we were queued for save
          (save-current-snapshot!))))))

(defn- sources-array [channels]
  (apply array
         (for [i (range 4)
               :let [c (keyword (str "channel" i))]]
           (get-in channels [c :source]))))

(declare update-current-point-info!)

(defn derive-color-channels
  "Given a collection of loaded resources, determines which additional color channels
  should be shown"
  [resources]
  (let [addon-field-sets (mapv (fn [{:keys [:schema]}]
                                 (set (filter :addon schema)))
                               resources)]

    (vec (for [{n :name} (apply set/union addon-field-sets)]
           [(str "local://field-color?field=" n)
            n]))))

(def ^:private z-vec (array 0 0 -1))

(defn initialize-for-resource<! [e {:keys [server resource ro render-hints init-params]}]
  (go
    (let [allow-greyhound-creds? (true? (:allowGreyhoundCredentials init-params))
          init-options-js-obj (js-obj "server" server
                                      "resource" (if (sequential? resource) (into-array resource) resource)
                                      "brushes" (sources-array (:channels ro))
                                      "allowGreyhoundCredentials" allow-greyhound-creds?
                                      "disableSplitLimiting" true #_(:no-split-limiting ro)
                                      "cameraChangeCallbackFn" (fn [eye target final? applying-state?]
                                                                 ;; when the state is final and we're not applying a state, make a history
                                                                 ;; record of this
                                                                 ;;
                                                                 (when (and final?
                                                                            (not applying-state?)
                                                                            (:useBrowserHistory init-params))
                                                                   (do-save-current-snapshot)))
                                      "rendererOptions" (js-obj "clearColor" (array 0 (/ 29 256) (/ 33 256))
                                                                "circularPoints" 0
                                                                "pointSize" (:point-size ro)
                                                                "pointSizeAttenuation" (array 1 (:point-size-attenuation ro))
                                                                "xyzScale" (array 1 1 (get-in init-params [:pm :z-exaggeration])))
                                      "initialCameraParams" (when (-> init-params :camera seq)
                                                              (js-camera-props (:camera init-params))))

          point-cloud-viewer (js/Plasio.PointCloudViewer.
                               e
                               init-options-js-obj)
          ;; wait for the renderer to initialize and start
          info (<! (util/wait-promise< (.start point-cloud-viewer)))

          ;; Pull out some props we need
          mode-manager (.getModeManager point-cloud-viewer)
          camera (aget mode-manager "activeCamera")

          ;; Pull out the geo transform we need to compute geo space coordinates
          geo-transform (.getGeoTransform point-cloud-viewer)]

      ;; Only when the point cloud viewer started correctly
      (when info
        ;; store resource info for use later.
        (om/update! resource-info (js->clj info :keywordize-keys true))

        ;; listen to any synthetic point clicks, on the camera mode to show point information
        ;; only enabled for single resource viewing, multiple resources cause too much greyhound overhead.
        (when (= 1 (.-length info))
          (.registerHandler camera
                            "synthetic-click-on-point"
                            (util/throttle
                              200
                              (fn [obj]
                                (update-current-point-info! (js->clj (aget obj "pointPos")))))))

        ;; mode manager will let us know about any context menu actions we
        ;; need to handle
        (.addActionListener mode-manager
                            (fn [actions info]
                              ;; if we were provided with actions then show them
                              ;; otherwise we show our own list
                              (let [acts (js->clj actions :keywordize-keys true)
                                    info (js->clj info :keywordize-keys true)
                                    actions-to-use (if (empty? acts)
                                                     ;; no actions from any of the modes, provide our
                                                     ;; own actions
                                                     {:camera ["Camera" #(set! (.-activeMode mode-manager) "camera")]
                                                      :lines  ["Pick Line Segments" #(set! (.-activeMode mode-manager) "line")]
                                                      :point  ["Pick Points" #(set! (.-activeMode mode-manager) "point")]}
                                                     acts)]

                                ;; make sure all actions can dismiss the popup
                                (om/update! current-actions
                                            {:actions   (into {}
                                                              (for [[k [title f]] actions-to-use]
                                                                [k [title (wrap-dismiss-context-menu f)]]))
                                             :pos       (:pos info)
                                             :screenPos (:screenPos info)}))))

        ;; Continously monitor our target location and compute our compass stats
        (let [renderer (.getRenderer point-cloud-viewer)]
          (.addPropertyListener renderer (array "view")
                                (fn [view]
                                  (when view
                                    (let [eye (aget view "eye")
                                          target (aget view "target")]
                                      ;; such calculations, mostly project vectors to xz plane and
                                      ;; compute the angle between the two vectors
                                      (when (and eye target)
                                        (let [plane (math/target-plane target)       ;; plane at target
                                              peye (math/project-point plane eye)    ;; project eye
                                              v (math/make-vec target peye)          ;; vector from target to eye
                                              theta (math/angle-between z-vec v)     ;; angle between target->eye and z
                                              theta (math/->deg theta)               ;; in degrees

                                              t->e (math/make-vec target eye)        ;; target->eye vector
                                              t->pe (math/make-vec target peye)      ;; target->projected eye vector
                                              incline (math/angle-between t->e t->pe)  ;; angle between t->e and t->pe
                                              incline (math/->deg incline)]            ;; in degrees

                                          ;; make sure the values are appropriately adjusted for them to make sense as
                                          ;; css transforms
                                          (let [transformed (.transform geo-transform target "render" "geo")]
                                            (om/update! target-location [(aget transformed 0)
                                                                         (aget transformed 1)
                                                                         (aget transformed 2)]))
                                          (om/transact! compass #(assoc % :heading
                                                                          (if (< (aget v 0) 0)
                                                                            theta
                                                                            (- 360 theta))
                                                                          :incline
                                                                          (- 90 (max 20 incline)))))))))))

        ;; set some startup conditions on point cloud viewer
        (when-not (str/blank? (:filter ro))
          (if-let [filter (try (js/JSON.parse (:filter ro))
                               (catch js/Error _ nil))]
            (.setFilter point-cloud-viewer filter)
            (js/console.warn "The filter could not be applied because it couldn't be parsed")))

        ;; TODO: This is TEMPORARY
        #_(components/set-active-autotool! :profile renderer {})

        ;; establish a listener for lines, just blindly accept lines and mutate our internal
        ;; state with list of lines
        #_(.addPropertyListener
            renderer (array "line-segments")
            (fn [segments]
              (reset! app-state-lines segments)))

        ;; get all loaded resources along with their IDs
        (let [resources (js->clj (.getLoadedResources point-cloud-viewer)
                                 :keywordize-keys true)]
          (om/update! loaded-resources resources))

        ;; if we've been asked to derive fields from color channels, then create a channels array based on
        ;; additional fields
        (when (:deriveColorChannelsFromAddonFields init-params)
          (om/transact! root #(assoc-in % [:init-params :derived-color-channels] (derive-color-channels @loaded-resources))))

        ;; return components we have here
        {:target-element     e
         :renderer           (.getRenderer point-cloud-viewer)
         :point-cloud-viewer point-cloud-viewer
         :mode-manager       mode-manager}))))


(defn show-search-box! []
  (om/transact! ui-local-options
                #(assoc % :search-box-visible? true)))

(defn toggle-search-box! []
  (om/transact! ui-local-options
                #(update % :search-box-visible? not)))

(defn hide-search-box! []
  (om/transact! ui-local-options
                #(assoc % :search-box-visible? false)))


(def mapbox-token "pk.eyJ1IjoiaG9idSIsImEiOiItRUhHLW9NIn0.RJvshvzdstRBtmuzSzmLZw")

(defn resolve-address [address]
  (let [escaped (js/encodeURIComponent address)
        url (str "https://api.mapbox.com/v4/geocode/mapbox.places/"
                 address ".json?access_token=" mapbox-token)]
    (go
      (when-let [res (some-> url
                             (http/get {:with-credentials? false})
                             <!
                             :body
                             js/JSON.parse
                             (js->clj :keywordize-keys true)
                             (get-in [:features 0]))]
        {:coordinates (:center res)
         :address (:place_name res)}))))

(defn- world-x-range [bounds]
  (let [sx (bounds 0)
        ex (bounds 3)
        center (+ sx (/ (- ex sx) 2))]
    [sx ex center]))

(defn- fix-easting [bounds x]
  (let [[minx maxx midx] (world-x-range bounds)]
    (- (* midx 2) x)))

(defn data-range [bounds]
  [(- (bounds 3) (bounds 0))
   (- (bounds 4) (bounds 1))
   (- (bounds 5) (bounds 2))])

(defn transition-to [x y]
  (let [bounds (util/resources-bounds @resource-info)
        [rx ry _] (data-range bounds)
        x' (util/mapr (fix-easting bounds x)
                      (bounds 0) (bounds 3)
                      (- (/ rx 2)) (/ rx 2))
        y' (util/mapr y (bounds 1) (bounds 4)
                      (- (/ ry 2)) (/ rx 2))
        camera (.-activeCamera (:mode-manager @comps))]
    ;; may need to do something about easting
    (.transitionTo camera x' nil y')))


(defn set-channel-source! [channel source]
  ;; setting a new source on a channel will wipe out all settings
  (om/transact! ro #(assoc-in % [:channels channel] {:source source})))

(defn set-channel-contribution! [channel source]
  (om/transact! ro #(assoc-in % [:channels channel :contribution] source)))

(defn mute-channel! [channel mute?]
  (om/transact! ro #(-> %
                        (assoc-in [:channels channel :mute?] mute?)
                        (assoc-in [:channels channel :solo?] false))))

(defn solo-channel! [channel solo?]
  (om/transact! ro #(-> %
                        (assoc-in [:channels channel :solo?] solo?)
                        (assoc-in [:channels channel :mute?] false))))

(defn set-channel-ramp! [channel ramp]
  (om/transact! ro #(assoc-in % [:channels channel :range-clamps] ramp)))

(defn mkjson [v]
  (js/JSON.stringify (clj->js v)))

(defn- ru32 [buff offset]
  (let [b (js/DataView. buff offset)]
    (aget b 0)))

(defn- point-count [arraybuffer]
  (let [dv (js/DataView. arraybuffer (- (.-byteLength arraybuffer) 4))]
    (.getUint32 dv 0 true)))

(let [fn-types {"signed" "Int", "unsigned" "Uint", "floating" "Float"}]
  (defn- decode-val [type size dv offset]
    (let [fn-name (str "get"
                       (fn-types type)
                       (* size 8))
          f (aget dv fn-name)]
      (.call f dv offset true))))

(defn- decode-point [dv schema]
  (loop [offset 0
         point []
         s schema]
    (if (seq s)
      (let [{:keys [name size type addon]} (first s)]
        (recur
         (+ offset size)
         (conj point [name size (decode-val type size dv offset) addon])
         (rest s)))
      point)))

(defn- decode-points [schema arraybuffer]
  (let [pc (point-count arraybuffer)
        schema-size (util/schema->point-size schema)
        points (into []
                     (for [i (range pc)
                           :let [offset (* i schema-size)
                                 dv (js/DataView. arraybuffer offset schema-size)]]
                       (decode-point dv schema)))]
    points))

(defn point-info-for-resource< [point-cloud-viewer resource-info allowGreyhoundCredentials loc]
  (go
    (if (:eptRootUrl resource-info)
      (merge (select-keys resource-info [:eptRootUrl])
             {:error ::ept-not-supported})
      (let [geotransform (.getGeoTransform point-cloud-viewer)
            geo-space-loc (.transform geotransform (apply array loc) "render" "geo")

            ;; we only query non-addon fields
            ;;
            schema (:schema resource-info)

            delta 0.1                                       ; this probably needs to be something based on the data range
            bounds [(- (aget geo-space-loc 0) delta) (- (aget geo-space-loc 1) delta) (- (aget geo-space-loc 2) delta)
                    (+ (aget geo-space-loc 0) delta) (+ (aget geo-space-loc 1) delta) (+ (aget geo-space-loc 2) delta)]

            url (str (util/join-url-parts (js/Plasio.Util.pickOne (:server resource-info)) "resource" (:resource resource-info) "read")
                     "?"
                     "bounds=" (js/encodeURIComponent (mkjson bounds)) "&"
                     "schema=" (js/encodeURIComponent (mkjson schema)) "&"
                     "depthBegin=0&depthEnd=30" "&"
                     "compress=false")
            res (<! (util/binary-http-get< url {:with-credentials? allowGreyhoundCredentials}))
            point (when res (first (decode-points schema res)))
            point (into {} (map-indexed (fn [index [name _ val addon?]]
                                          [(keyword (str/lower-case name)) {:displayName name :val val :index index :addon? addon?}])
                                        point))]
        ;; when we have a point load up its info
        (when (seq point)
          (let [ret-point (if-let [origin-id (get point :originid)]
                            (let [href (util/join-url-parts (js/Plasio.Util.pickOne (:server resource-info)) "resource"
                                                            (:resource resource-info) "files" (:val origin-id))
                                  res (-> href
                                          (http/get {:with-credentials? allowGreyhoundCredentials})
                                          <!)
                                  json (when (:success res)
                                         (:body res))]
                              ;; if we loaded data and we're still looking at the point that was last loaded transact the
                              ;; metadata in
                              (if (seq json)
                                (assoc point :x-point-metadata {:href      href
                                                                :path      (:path json)
                                                                :bounds    (:bounds json)
                                                                :numPoints (:numPoints json)
                                                                :inserts   (-> json :pointStats :inserts)})
                                point))
                            point)]
            (merge point (select-keys resource-info [:server :resource]))))))))

(defn update-current-point-info! [loc]
  (when-let [point-cloud-viewer (:point-cloud-viewer @comps)]
    (om/update! root :clicked-point-load-in-progress? true)
    (go
      ;; query all resources and determine what they have to say about the clicked point
      (let [allow-creds (-> @root :init-params :allowGreyhoundCredentials)
            chans (map #(point-info-for-resource< point-cloud-viewer % allow-creds loc) (:resource-info @root))
            results (<! (async/into [] (async/merge chans)))]
        (om/update! root :clicked-point-load-in-progress? false)
        (om/update! clicked-point-info results)

        results))))

(defn- encode-params [{:keys [:s :r] :as params}]
  ;; encode server and resource params first for usability purposes
  (let [ef #(str (name %1) "=" (js/encodeURIComponent %2))]
    (str/join "&" (concat [(ef "s" s) (ef "r" (if (sequential? r)
                                                (str/join "," r)
                                                r))]
                          (for [[k v] (dissoc params :s :r)]
                            (ef k v))))))

(defn- make-resource-url [{:keys [:server-url :name :params :queryString]}]
  (let [current-origin (.. js/window -location -origin)
        params-from-qs (if-not (str/blank? queryString) (util/qs->params queryString) {})
        final-params (merge params params-from-qs)]
    ;; always override server and resource (qs may have them but we don't care about those).
    (str current-origin "?" (encode-params (assoc final-params :s server-url :r name)))))

(defn load-available-resources<! [specified-resources]
  (go
    (let [_ (println "Fetching resources from resources.json, pre-specified resources:" specified-resources)
          data (if (seq specified-resources)
                 specified-resources
                 (-> "resources.json" http/get <! :body))
          _ (println "Resources payload is:" data)
          {:keys [:servers :resources]} data
          server-map (into {} (for [{:keys [:name :url]} (:items servers)
                                    :when (not (str/blank? name))]
                                [name (util/urlify url)]))
          resource-defaults (:defaults resources {})
          resources (->> (:items resources)
                         (mapv (fn [{:keys [:name] :as r}]
                                 (let [rr (merge resource-defaults r)
                                       server-url (get server-map (:server rr))
                                       rr (assoc rr :server-url server-url)]
                                   (assoc rr
                                     :id (str name "@" (:server rr))
                                     :url (make-resource-url rr))))))]
      (om/update! available-resources resources)
      resources)))


(defn load-available-filters<! [specified-filters]
  (go
    (let [filters (if (seq specified-filters)
                    specified-filters
                    (-> "filters.json" http/get <! :body))]
      (om/update! available-filters filters)
      filters)))


(defn apply-filter! [filter-as-string]
  (let [viewer (-> @comps :point-cloud-viewer)
        json (when-not (str/blank? filter-as-string)
               (js/JSON.parse filter-as-string))]
    (om/transact! ro #(assoc % :filter filter-as-string))
    #_(when viewer
      (.setFilter viewer json))))

(defn- set-resource-visibility-internal
  [key show? exclusive?]
  (let [viewer (-> @comps :point-cloud-viewer)]
     (om/transact! loaded-resources (fn [rs]
                                      (mapv #(if (= (:key %) key)
                                               (assoc % :visible show?)
                                               (if exclusive?
                                                 (assoc % :visible (not show?))
                                                 %))
                                            rs)))
     #_(om/transact! animation-settings #(assoc % :scrubbing? false :playing? false))
     (doseq [r @loaded-resources]
       (.setResourceVisibility viewer (:key r) (:visible r)))))

(defn set-resource-visibility
  ([key show?]
    (set-resource-visibility key show? false))
  ([key show? exclusive?]
   (om/transact! animation-settings #(assoc % :playing? false :scrubbing? false))
   (set-resource-visibility-internal key show? exclusive?)))


(defprotocol IAnimator
  ;; An animator is responsible for animating through a series of frames
  ;; update the animator with current time, time-delta is in milliseconds increasing chronologically
  ;; calling update on an animator will reset its state to playing
  (-update! [_ time])

  ;; get the current  frame that should be shown based on this animator
  (-current-frame [_])

  ;; scrub offset for current state
  (-scrub-offset [_])

  ;; set the current scrub factor from 0 -> 1, sets the state to scrubbing
  (-set-scrub! [_ f])

  ;; set a param for the animator
  (-set-param! [_ key value]))


(defrecord StepAnimator [ref-cursor]
  IAnimator
  (-update! [_ now]
    (let [delta (* 1000 (/ 1 (or (-> @ref-cursor :params :frame-rate) 5)))
          frame-count (-> @ref-cursor :params :frame-count)
          last-frame-time (-> @ref-cursor :runtime :last-frame-time)]
      (when (and (or (not last-frame-time)
                     (> (- now last-frame-time) delta)))
          ;; time to update frame
          (om/transact!
            ref-cursor #(update % :runtime
                                (fn [runtime]
                                  (-> runtime
                                      (update :current-frame (fn [index]
                                                               (let [i (if index
                                                                         (inc index)
                                                                         0)]
                                                                 (if (= i frame-count)
                                                                   0
                                                                   i))))
                                      (assoc :last-frame-time now))))))))

  (-current-frame [_]
    (-> @ref-cursor :runtime :current-frame))

  (-scrub-offset [_]
    (/ (-> @ref-cursor :runtime :current-frame)
       (dec (-> @ref-cursor :params :frame-count))))

  (-set-scrub! [_ value]
    ;; determine frame based on the scrub value
    (let [frame-index (js/Math.floor (* value (dec (-> @ref-cursor :params :frame-count))))]
      (om/transact! ref-cursor
                    #(update % :runtime
                             (fn [runtime]
                               (-> runtime
                                   (assoc :current-frame frame-index)))))))

  (-set-param! [_ key value]
    (om/transact! ref-cursor #(update % :params
                                      (fn [params] (assoc params key value))))))


(defn- offset->frame-index [frames offset]
  (let [start (-> frames first :start)]
    (->> frames
         (filter #(<= (:start %)
                      (+ offset start)
                      (:end %)))
         first
         :index)))

(defn- frames->range [frames]
  [(-> frames first :start)
   (-> frames last :end)])

(def increments [2 3 4 5 6 7])
(defn multiplier-factor [m]
  (if (zero? m)
    1
    (let [mm (dec (js/Math.abs m))
          f (js/Math.pow 10 (nth increments mm))]
      (if (neg? m)
        (/ 1 f)
        f))))

(defrecord TimelineAnimator [ref-cursor]
  IAnimator
  (-update! [_ now]
    ;; timeline works by determining what the current frame should be on the timeline
    (let [params (-> @ref-cursor :params)
          {:keys [:frames :multiplier]} params
          {:keys [:last-frame-time :anim-time]} (-> @ref-cursor :runtime)
          [start end] (frames->range frames)
          multiplier (or multiplier 0)
          last-frame-time (or last-frame-time now)
          anim-time (or anim-time 0)

          time-delta (* (multiplier-factor multiplier) (- now last-frame-time))
          new-anim-time (+ anim-time time-delta)
          new-anim-time (if (> new-anim-time (- end start))
                          0 new-anim-time)
          offset new-anim-time]
      (om/transact! ref-cursor
                    :runtime
                    #(-> %
                         (assoc :offset offset
                                :scrub-offset (/ offset (- end start))
                                :current-frame (offset->frame-index frames offset)
                                :anim-time new-anim-time
                                :last-frame-time now)))))

  (-current-frame [_]
    (-> @ref-cursor :runtime :current-frame))

  (-scrub-offset [_]
    (-> @ref-cursor :runtime :scrub-offset))

  (-set-scrub! [_ value]
    (let [frames (-> @ref-cursor :params :frames)
          [start end] (frames->range frames)

          offset (* value (- end start))]
      (println start end offset (offset->frame-index frames offset))
      (om/transact! ref-cursor
                    :runtime
                    #(-> %
                         (assoc :offset offset)
                         (assoc :scrub-offset value)
                         (assoc :current-frame (offset->frame-index frames offset))))))

  (-set-param! [_ key value]
    (om/transact! ref-cursor #(update % :params
                                      (fn [params] (assoc params key value))))))

(defn setup-animator! [controller-type startup-params]
  (om/update! animation-runtime {:params startup-params
                                 :runtime {}})
  (let [animator ((case controller-type
                    :step map->StepAnimator
                    :timeline map->TimelineAnimator)
                   {:ref-cursor animation-runtime})]
    (om/transact! animation-settings
                  (fn [settngs]
                    {:controller controller-type
                     :controller-instance animator}))))

;; The animation controller relies on the animator to switch frames, doesn't
;; really do anything other than managing play/scrub state
;;

(defn- trigger-anim []
  (js/requestAnimationFrame
    (fn animation-frame [now]
      (let [resources @loaded-resources
            controller (:controller-instance @animation-settings)]
        ;; is it time to render this frame?
        (when (and (:playing? @animation-settings)
                   controller)
          ;; time to update frame
          (-update! controller now)

          ;; update the frame and scrub offset caused by this animation frame
          (om/update! animation-settings :current-frame (-current-frame controller) )
          (om/update! animation-settings :scrub-offset (-scrub-offset controller) )

          ;; use the newly computed animation frame to set visibility
          (let [key (-> @loaded-resources
                        (nth (:current-frame @animation-settings))
                        :key)]
            (set-resource-visibility-internal key true true)))

        ;; while we are still playing, schedule next frame
        (when (:playing? @animation-settings)
          (js/requestAnimationFrame animation-frame))))))

(defn anim-play []
  (om/transact! animation-settings #(assoc % :playing? true :scrubbing? false))
  (trigger-anim))

(defn anim-stop []
  (om/transact! animation-settings #(assoc % :playing? false :scrubbing? false))
  (om/transact! animation-runtime #(assoc % :runtime {})))


(defn anim-set-param! [key value]
  (om/transact! animation-settings :params #(assoc % key value))
  (when-let [c (:controller-instance @animation-settings)]
    (-set-param! c key value)))

(defn anim-set-current-scrub-offset!
  "We may be asked to specifically set the current the current frame which means me
   need to stop animating and set the current frame to the specified index"
  [offset]
  (let [offset (max 0 (min 1 offset))]
    (when-let [ci (:controller-instance @animation-settings)]
      (-set-scrub! ci offset)
      (om/transact! animation-settings
                    #(assoc % :playing? false :scrubbing? true
                              :current-frame (-current-frame ci)
                              :scrub-offset offset))
      (let [key (-> @loaded-resources
                    (nth (:current-frame @animation-settings))
                    :key)]
        (set-resource-visibility-internal key true true)))))

(defn anim-set-controller! [controller]
  (let [frames (->> (-> @root :init-params :resource-info :frames)
                    (map (fn [f] (update f :ts js/Date.parse))) ;; parse time stamps
                    (sort-by :ts)                           ;; make sure frames are in order
                    (partition-all 2 1)                     ;; pair them up, so that we can set start and end
                    (map-indexed (fn [index [a b]]                        ;; set each frame's start and end period
                                   (assoc a :index index
                                            :start (:ts a)
                                            :end (if b (dec (:ts b))
                                                       (+ (:ts a) (* 24 60 60 1000)))))))]
    (setup-animator! controller {:frame-count (count @loaded-resources)
                                 :frames      (vec frames)})))

(defn anim-set-default-controller! []
  (when-not (:controller @animation-settings)
    (anim-set-controller! :step)))
