;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api
  "A WASM based render API"
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.svg.path :as path]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.render-wasm.helpers :as h]
   [app.util.debug :as dbg]
   [app.util.functions :as fns]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [goog.object :as gobj]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defonce internal-frame-id nil)
(defonce internal-module #js {})
(defonce use-dpr? (contains? cf/flags :render-wasm-dpr))

(def dpr
  (if use-dpr? js/window.devicePixelRatio 1.0))

(mf/defc svg-raw-element
  {::mf/props :obj}
  [{:keys [tag attrs content] :as props}]
  [:& (name tag) attrs
   (for [child content]
     (if (string? child)
       child
       [:& svg-raw-element child]))])

(mf/defc svg-raw
  {::mf/props :obj}
  [{:keys [shape] :as props}]
  (let [content (:content shape)]
    [:svg {:version "1.1"
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :fill "none"}

     ;; FIXME: Not sure if this is needed
     #_[:defs
      [:& fills/fills            {:shape shape :render-id "render-id"}]]

     (if (string? content)
       content
       [:& svg-raw-element content])]))

(mf/defc svg-path
  {::mf/props :obj}
  [{:keys [shape] :as props}]
  [:svg {:version "1.1"
         :xmlns "http://www.w3.org/2000/svg"
         :xmlnsXlink "http://www.w3.org/1999/xlink"
         :fill "none"}
   [:> shape-container {:shape shape}
    [:& path-shape {:shape shape}]]])

(defn get-static-markup
  [shape]
  (rds/renderToStaticMarkup
   (case (:type shape)
     :svg-raw (mf/element svg-raw #js {:shape shape})
     :path (mf/element svg-path #js {:shape shape}))))

;; This should never be called from the outside.
;; This function receives a "time" parameter that we're not using but maybe in the future could be useful (it is the time since
;; the window started rendering elements so it could be useful to measure time between frames).
(defn- render
  [_]
  (h/call internal-module "_render")
  (set! internal-frame-id nil))

(defn- render-without-cache
  [_]
  (h/call internal-module "_render_without_cache")
  (set! internal-frame-id nil))

(defn- rgba-from-hex
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns its 32-bit rgba representation"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))]
        ;; rgba >>> 0 so we have an unsigned representation
    (unsigned-bit-shift-right (bit-or (bit-shift-left a 24) rgb) 0)))

(defn- rgba-bytes-from-hex
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns an array with its r g b a values"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))
        ;; rgba >>> 0 so we have an unsigned representation
        r (bit-shift-right rgb 16)
        g (bit-and (bit-shift-right rgb 8) 255)
        b (bit-and rgb 255)]
    [r g b a]))

(defn cancel-render
  []
  (when internal-frame-id
    (js/cancelAnimationFrame internal-frame-id)
    (set! internal-frame-id nil)))

(defn request-render
  []
  (when internal-frame-id (cancel-render))
  (set! internal-frame-id (js/requestAnimationFrame render)))

(defn use-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call internal-module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-clip-content
  [clip-content]
  (h/call internal-module "_set_shape_clip_content" clip-content))

(defn set-shape-type
  [type]
  (cond
    (= type :circle)
    (h/call internal-module "_set_shape_kind_circle")

    (= type :path)
    (h/call internal-module "_set_shape_kind_path")

    :else
    (h/call internal-module "_set_shape_kind_rect")))

(defn set-shape-selrect
  [selrect]
  (h/call internal-module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn set-shape-transform
  [transform]
  (h/call internal-module "_set_shape_transform"
          (dm/get-prop transform :a)
          (dm/get-prop transform :b)
          (dm/get-prop transform :c)
          (dm/get-prop transform :d)
          (dm/get-prop transform :e)
          (dm/get-prop transform :f)))

(defn set-shape-rotation
  [rotation]
  (h/call internal-module "_set_shape_rotation" rotation))

(defn set-shape-children
  [shape-ids]
  (h/call internal-module "_clear_shape_children")
  (run! (fn [id]
          (let [buffer (uuid/get-u32 id)]
            (h/call internal-module "_add_shape_child"
                    (aget buffer 0)
                    (aget buffer 1)
                    (aget buffer 2)
                    (aget buffer 3))))
        shape-ids))

(defn- get-string-length [string] (+ (count string) 1))

;; IMPORTANT: We need to take into account that we can only store TTF fonts.
(defn- store-font
  [family-name font-array-buffer]
  (let [family-name-size (get-string-length family-name)
        font-array-buffer-size (.-byteLength font-array-buffer)
        size (+ font-array-buffer-size family-name-size)
        ptr  (h/call internal-module "_alloc_bytes" size)
        family-name-ptr (+ ptr font-array-buffer-size)
        heap (gobj/get ^js internal-module "HEAPU8")
        mem  (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. font-array-buffer))
    (h/call internal-module "stringToUTF8" family-name family-name-ptr family-name-size)
    (h/call internal-module "_store_font" family-name-size font-array-buffer-size)))

;; This doesn't work
#_(store-font-url "roboto-thin-italic" "https://fonts.gstatic.com/s/roboto/v32/KFOiCnqEu92Fr1Mu51QrEzAdLw.woff2")
;; This does
#_(store-font-url "sourcesanspro-regular" "http://localhost:3449/fonts/sourcesanspro-regular.ttf")
(defn- store-font-url
  [family-name font-url]
  (-> (p/then (js/fetch font-url)
              (fn [response] (.arrayBuffer response)))
      (p/then (fn [array-buffer] (store-font family-name array-buffer)))))

(defn- store-image
  [id]
  (let [buffer (uuid/get-u32 id)
        url    (cf/resolve-file-media {:id id})]
    (->> (http/send! {:method :get
                      :uri url
                      :response-type :blob})
         (rx/map :body)
         (rx/mapcat wapi/read-file-as-array-buffer)
         (rx/map (fn [image]
                   (let [image-size (.-byteLength image)
                         image-ptr  (h/call internal-module "_alloc_bytes" image-size)
                         heap       (gobj/get ^js internal-module "HEAPU8")
                         mem        (js/Uint8Array. (.-buffer heap) image-ptr image-size)]
                     (.set mem (js/Uint8Array. image))
                     (h/call internal-module "_store_image"
                             (aget buffer 0)
                             (aget buffer 1)
                             (aget buffer 2)
                             (aget buffer 3)
                             image-size)
                     true))))))

(defn set-shape-fills
  [fills]
  (h/call internal-module "_clear_shape_fills")
  (keep (fn [fill]
          (let [opacity  (or (:fill-opacity fill) 1.0)
                color    (:fill-color fill)
                gradient (:fill-color-gradient fill)
                image    (:fill-image fill)]

            (cond
              (some? color)
              (let [rgba (rgba-from-hex color opacity)]
                (h/call internal-module "_add_shape_solid_fill" rgba))

              (some? gradient)
              (let [stops     (:stops gradient)
                    n-stops   (count stops)
                    mem-size  (* 5 n-stops)
                    stops-ptr (h/call internal-module "_alloc_bytes" mem-size)
                    heap      (gobj/get ^js internal-module "HEAPU8")
                    mem       (js/Uint8Array. (.-buffer heap) stops-ptr mem-size)]
                (if (= (:type gradient) :linear)
                  (h/call internal-module "_add_shape_linear_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity)
                  (h/call internal-module "_add_shape_radial_fill"
                          (:start-x gradient)
                          (:start-y gradient)
                          (:end-x gradient)
                          (:end-y gradient)
                          opacity
                          (:width gradient)))
                (.set mem (js/Uint8Array. (clj->js (flatten (map (fn [stop]
                                                                   (let [[r g b a] (rgba-bytes-from-hex (:color stop) (:opacity stop))
                                                                         offset (:offset stop)]
                                                                     [r g b a (* 100 offset)]))
                                                                 stops)))))
                (h/call internal-module "_add_shape_fill_stops" stops-ptr n-stops))

              (some? image)
              (let [id            (dm/get-prop image :id)
                    buffer        (uuid/get-u32 id)
                    cached-image? (h/call internal-module "_is_image_cached" (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))]
                (h/call internal-module "_add_shape_image_fill"
                        (aget buffer 0)
                        (aget buffer 1)
                        (aget buffer 2)
                        (aget buffer 3)
                        opacity
                        (dm/get-prop image :width)
                        (dm/get-prop image :height))
                (when (== cached-image? 0)
                  (store-image id))))))
        fills))

(defn set-shape-path-content
  [content]
  (let [buffer    (path/content->buffer content)
        size      (.-byteLength buffer)
        ptr       (h/call internal-module "_alloc_bytes" size)
        heap      (gobj/get ^js internal-module "HEAPU8")
        mem       (js/Uint8Array. (.-buffer heap) ptr size)]
    (.set mem (js/Uint8Array. buffer))
    (h/call internal-module "_set_shape_path_content")))

(defn set-shape-svg-raw-content
  [content]
  (let [size (get-string-length content)
        ptr (h/call internal-module "_alloc_bytes" size)]
    (h/call internal-module "stringToUTF8" content ptr size)
    (h/call internal-module "_set_shape_svg_raw_content")))

(defn set-shape-content
  [shape content]
  (let [type (:type shape)]
    (cond
      (and (some? content)
           (or (= type :svg-raw)
               (and (= type :path) (d/not-empty? (:svg-attrs shape)))))
      (set-shape-svg-raw-content (get-static-markup shape))

      (= type :path)
      (set-shape-path-content content))))

(defn- translate-blend-mode
  [blend-mode]
  (case blend-mode
    :normal 3
    :darken 16
    :multiply 24
    :color-burn 19
    :lighten 17
    :screen 14
    :color-dodge 18
    :overlay 15
    :soft-light 21
    :hard-light 20
    :difference 22
    :exclusion 23
    :hue 25
    :saturation 26
    :color 27
    :luminosity 28
    3))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (h/call internal-module "_set_shape_blend_mode" (translate-blend-mode blend-mode)))

(defn set-shape-opacity
  [opacity]
  (h/call internal-module "_set_shape_opacity" (or opacity 1)))

(defn set-shape-hidden
  [hidden]
  (h/call internal-module "_set_shape_hidden" hidden))

(def debounce-render-without-cache (fns/debounce render-without-cache 100))

(defn set-view
  [zoom vbox]
  (h/call internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
  (h/call internal-module "_navigate")
  (debounce-render-without-cache))

(defn set-objects
  [objects]
  (let [shapes        (into [] (vals objects))
        total-shapes  (count shapes)
        pending
        (loop [index 0 pending []]
          (if (< index total-shapes)
            (let [shape        (nth shapes index)
                  id           (dm/get-prop shape :id)
                  type         (dm/get-prop shape :type)
                  selrect      (dm/get-prop shape :selrect)
                  clip-content (not (dm/get-prop shape :show-content))
                  rotation     (dm/get-prop shape :rotation)
                  transform    (dm/get-prop shape :transform)
                  fills        (if (= type :group)
                                 [] (dm/get-prop shape :fills))
                  children     (dm/get-prop shape :shapes)
                  blend-mode   (dm/get-prop shape :blend-mode)
                  opacity      (dm/get-prop shape :opacity)
                  hidden       (dm/get-prop shape :hidden)
                  content      (dm/get-prop shape :content)]

              (use-shape id)
              (set-shape-type type)
              (set-shape-clip-content clip-content)
              (set-shape-selrect selrect)
              (set-shape-rotation rotation)
              (set-shape-transform transform)
              (set-shape-blend-mode blend-mode)
              (set-shape-children children)
              (set-shape-opacity opacity)
              (set-shape-hidden hidden)
              (set-shape-content shape content)

              (let [pending-fills (doall (set-shape-fills fills))]
                (recur (inc index) (into pending pending-fills))))
            pending))]
    (request-render)
    (when-let [pending (seq pending)]
      (->> (rx/from pending)
           (rx/mapcat identity)
           (rx/reduce conj [])
           (rx/subs! request-render)))))

(def ^:private canvas-options
  #js {:antialias false
       :depth true
       :stencil true
       :alpha true})

(defn clear-canvas
  [])
  ;; TODO: perform corresponding cleaning


(defn resize-viewbox
  [width height]
  (h/call internal-module "_resize_viewbox" width height))

(defn- debug-flags
  []
  (cond-> 0
    (dbg/enabled? :wasm-viewbox)
    (bit-or 2r00000000000000000000000000000001)))

(defn assign-canvas
  [canvas]
  (let [gl      (unchecked-get internal-module "GL")
        flags   (debug-flags)
        context (.getContext ^js canvas "webgl2" canvas-options)

        ;; Register the context with emscripten
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})]
    (.makeContextCurrent ^js gl handle)

    ;; Initialize Wasm Render Engine
    (h/call internal-module "_init" (/ (.-width ^js canvas) dpr) (/ (.-height ^js canvas) dpr))
    (h/call internal-module "_set_render_options" flags dpr))

  (set! (.-width canvas) (* dpr (.-clientWidth ^js canvas)))
  (set! (.-height canvas) (* dpr (.-clientHeight ^js canvas))))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-static-asset "js/render_wasm.js")]
        (->> (js/dynamicImport (str uri))
             (p/mcat (fn [module]
                       (let [default (unchecked-get module "default")]
                         (default))))
             (p/fmap (fn [module]
                       (set! internal-module module)
                       true))
             (p/merr (fn [cause]
                       (js/console.error cause)
                       (p/resolved false)))))
      (p/resolved false))))
