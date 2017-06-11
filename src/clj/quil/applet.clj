(ns ^{:doc "Functions and macros for initialising and controlling visualisation applets."}
  quil.applet
  (:import [processing.core PApplet]
           [javax.swing JFrame]
           [java.awt Dimension GraphicsEnvironment]
           [java.awt.event WindowListener])
  (:require [quil.util :refer [resolve-constant-key no-fn absolute-path]]
            [quil.middlewares
             [deprecated-options :refer [deprecated-options]]
             [safe-fns :refer [safe-fns]]
             [bind-output :refer [bind-output]]]
            [clojure.string :as string]))

(defonce untitled-applet-id* (atom 0))
(def ^:dynamic *applet* nil)

(defn ^PApplet current-applet []
  *applet*)

(defmacro with-applet
  "Binds dynamic var to current applet."
  [applet & body]
  `(binding [*applet* ~applet]
     ~@body))

(defn target-frame-rate []
  (:target-frame-rate (meta (current-applet))))

(def ^:private  executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor))

(defn- destroy-window
  "Cleanup native window object once sketch was closed. Processing doesn't perform cleanups
  because its probably assumes that when sketch is closed - JVM is closed as well which is
  not the case for clojure. So here we're performing renderer-specific cleanups. "
  [applet]
  (let [native (-> applet .getSurface .getNative)]
    (condp = (.getClass native)

      com.jogamp.newt.opengl.GLWindow
      ; Cannot destroy GLWindow right away because there is some callbacks going on
      ; and NPE is thrown when they execute if window is destroyed. It doesn't seem
      ; to affect anything, but annoying to have NPE in logs. Delaying destroying
      ; window for 1 sec. Ugly hack, but couldn't think of better way. Suggestions welcome.
      (.schedule executor #(.destroy native) 1 java.util.concurrent.TimeUnit/SECONDS)

      processing.awt.PSurfaceAWT$SmoothCanvas
      (-> native .getFrame .dispose)

      :nothing)))

(defn applet-disposed
  "This function is called when PApplet executes 'dispose' method.
  It means we can dispose frame, call on-close function and perform other
  clean ups."
  [applet]
  (with-applet applet
    ((:on-close (meta applet))))
  (-> applet .getSurface(.setVisible false))
  (destroy-window applet))

(defn applet-state
  "Fetch an element of state from within the applet"
  [applet k]
  (get @(:state (meta applet)) k))

(defn- prepare-applet-surface
  [applet title]
  (let [m              (meta applet)
        keep-on-top?   (:keep-on-top m)
        surface        (.getSurface applet)
        frame          (.frame applet)
        resizable?     (:resizable m)]
    ; TODO: check if resizable and alwaysOnTop work correctly.
    (javax.swing.SwingUtilities/invokeLater
     (fn []
       (.setResizable surface resizable?)
       (.setAlwaysOnTop surface keep-on-top?)))
    applet))


(defn- applet-run
  "Launches the applet to the specified target."
  [applet title renderer]
  (PApplet/runSketch
   (into-array String
               (vec (filter string?
                            [(when (:display (meta applet))
                               (str "--display=" (:display (meta applet))))
                             (when (and (:bgcolor (meta applet))
                                        (:present (meta applet)))
                               (str "--window-color" "=" (str (:bgcolor (meta applet)))))
                             (when (:present (meta applet))
                               (str "--present"))
                             "--hide-stop" title])))
   applet)
  (prepare-applet-surface applet title))


(def ^{:private true}
  renderer-modes {:p2d    PApplet/P2D
                  :p3d    PApplet/P3D
                  :java2d PApplet/JAVA2D
                  :opengl PApplet/OPENGL
                  :pdf    PApplet/PDF
                  :dxf    PApplet/DXF})

(defn resolve-renderer
  "Converts keyword to Processing renderer string constant.
  This string can be passed to native Processing methods.
  If renderer passed as String - do nothing and simply return it"
  [renderer]
  (cond (keyword? renderer) (resolve-constant-key renderer renderer-modes)
        (string? renderer) renderer
        :default (throw (RuntimeException. ":renderer should be keyword or string"))))

(defn- validate-size
  "Checks that the size vector is exactly two elements. If not, throws
  an exception, otherwise returns the size vector unmodified."
  [size]
  (if (or (= size :fullscreen)
          (and (coll? size) (= 2 (count size))))
    size
    (throw (IllegalArgumentException.
            (str "Invalid size definition: " size
                 ". Was expecting :fullscreen or 2 elements vector: "
                 "[width height].")))))

(defn- to-method-name [keyword]
  "Converts keyword to java-style method symbol. :on-key-pressed => onKeyPressed"
  (-> keyword
      name
      (string/replace
       #"-."
       #(-> % string/upper-case (subs 1)))
      symbol))

(defn- parent-method [method]
  "Appends string 'Parent' to given symbol"
  (symbol (str method "Parent")))

(def listeners [:key-pressed
                :key-released
                :key-typed
                :mouse-pressed
                :mouse-released
                :mouse-moved
                :mouse-dragged
                :mouse-entered
                :mouse-exited
                :mouse-clicked
                :focus-gained
                :focus-lost])

;; *************************************************************
(defn -oscEvent
  ([this message]
   (println "osc message received! Message: " message "   addrpattern:" (.addrPattern message) "   typetag:" (.typetag message))
   (with-applet this
     ((:osc-event (.state this)) message))))

(gen-interface
 :name quil.OscP5
 :methods [[oscEvent [oscP5.OscMessage] Object]])

;; *************************************************************

(gen-class
  :name "quil.Applet"
  :implements [clojure.lang.IMeta quil.OscP5] ;;****************
  :extends processing.core.PApplet
  :state state
  :init quil-applet-init
  :constructors {[java.util.Map] []}
  :exposes-methods {keyTyped keyTypedParent
                    loop loopParent
                    mouseDragged mouseDraggedParent
                    keyPressed keyPressedParent
                    mouseExited mouseExitedParent
                    mouseClicked mouseClickedParent
                    mouseEntered mouseEnteredParent
                    mouseMoved mouseMovedParent
                    keyReleased keyReleasedParent
                    mousePressed mousePressedParent
                    focusGained focusGainedParent
                    frameRate frameRateParent
                    mouseReleased mouseReleasedParent
                    focusLost focusLostParent
                    noLoop noLoopParent
                    sketchFullScreen sketchFullScreenParent})

(defn -exitActual
  "Overriding PApplet.exitActual because we don't want it to call
   System.exit()."
  [this])

(defn -sketchFullScreen [this] (:present (meta this)))

(defn -quil-applet-init [state]
  [[] state])

(defn -meta [this]
  (.state this))

(defn -settings
  "Overriding PApplet.settings() to set size."
  [this]
  (let [{:keys [renderer size output-file settings-fn]} (meta this)
        renderer (resolve-renderer renderer)
        output-file (absolute-path output-file)]
    (if (= size :fullscreen)
      (.fullScreen this renderer)
      (.size this (int (first size)) (int (second size))
             renderer output-file))
    ; setting pixel-density to support Retina display out-of-box.
    ; Cannot use quil.core/pixel-density due to cycle dependency.
    (.pixelDensity this (.displayDensity this))
    ; calling user-provided :settings handler, if any
    (with-applet this
      (settings-fn))))

(defn -setup [this]
  ; If renderer is :pdf - we need to set it via size method,
  ; as there is no other way to set file path for renderer.
  ; Size method call must be FIRST in setup function
  ; (don't know why, but let's trust Processing guys).
  ; Technically it's not first (there are 'when' and 'let' before 'size'),
  ; but hopefully it will work fine.
  (with-applet this
    ((:setup-fn (meta this)))))

(defn -draw [this]
  (with-applet this
    ((:draw-fn (meta this)))))

(defn -noLoop [this]
  (reset! (:looping? (meta this)) false)
  (.noLoopParent this))

(defn -loop [this]
  (reset! (:looping? (meta this)) true)
  (.loopParent this))

(defn -frameRate [this new-rate-target]
  (reset! (target-frame-rate) new-rate-target)
  (.frameRateParent this new-rate-target))

(defn -sketchRenderer [this]
  (let [renderer (:renderer (meta this))
        ; If renderer :pdf we can't use it as initial renderer
        ; as path to output file is not set and path can be set only
        ; via .size(width, height, renderer, path) method in setup function.
        ; Set :java2d renderer instead and call size method in setup later.
        initial-renderer (if (= renderer :pdf) :java2d renderer)]
      (resolve-renderer initial-renderer)))

(defmacro generate-listeners
  "Generates all listeners like onKeyPress, onMouseClick and others."
  []
  (letfn [(prefix [v method]
            (symbol (str v method)))
          (generate-listener [listener]
            (let [method (to-method-name listener)
                  parent-method-name (prefix "." (parent-method method))]
               `(defn ~(prefix "-" method)
                  ([~'this] (with-applet ~'this ((~listener (meta ~'this)))))
                  ([~'this ~'evt]
                   ; For all :key-xyz listeners we have to store event object
                   ; in applet state because later it might be used to
                   ; build set of key modifiers currently pressed.
                   ~(if (or (= listener :key-typed)
                            (= listener :key-pressed))
                      `(reset! (:key-event (meta ~'this)) ~'evt)
                      nil)
                   (~parent-method-name ~'this ~'evt)))))]
    `(do ~@(map generate-listener listeners))))

(generate-listeners)

(defn -mouseWheel [this evt]
  (with-applet this
    (when-let [mouse-wheel (:mouse-wheel (.state this))]
      (mouse-wheel (.getCount evt)))))

(defn attach-applet-listeners [applet]
  (let [listeners {:on-dispose #(applet-disposed applet)}
        listener-obj (quil.helpers.AppletListener. listeners)]
    (.registerMethod applet "dispose" listener-obj)
    applet))

(def ^{:private true}
  supported-features
  #{:resizable :exit-on-close :keep-on-top :present :no-safe-fns
    :no-bind-output})

(defn applet
  "Create and start a new visualisation applet. All options used
  here should be documented in 'defsketch' docstring."
  [& opts]
  (let [options (apply hash-map opts)

        options     (->> (:middleware options [])
                         (cons deprecated-options)
                         (apply comp)
                         (#(% options))
                         (merge {:size [500 300]}))

        features     (let [user-features (set (:features options))]
                       (reduce #(assoc %1 %2 (contains? user-features %2)) {}
                               supported-features))

        options     (if (:no-safe-fns features)
                      options
                      (safe-fns options))

        options     (if (:no-bind-output features)
                      options
                      (bind-output options))

        options           (merge (dissoc options :features)
                                 features)

        display           (or (:display options) :default)
        size              (validate-size (:size options))

        title             (or (:title options) (str "Quil " (swap! untitled-applet-id* inc)))
        renderer          (or (:renderer options) :java2d)
        draw-fn           (or (:draw options) no-fn)
        setup-fn          (or (:setup options) no-fn)
        settings-fn       (or (:settings options) no-fn)
        osc-event-fn      (or (:osc-event options) (fn [message] (println "default osc-event" message))) ;; ***************
        on-close-fn       (let [close-fn (or (:on-close options) no-fn)]
                            (if (:exit-on-close options)
                              (fn []
                                (close-fn)
                                (System/exit 0))
                              close-fn))

        state             (atom nil)
        looping?          (atom true)
        listeners         (into {} (for [name listeners]
                                     [name (or (options name) no-fn)]))

        applet-state      (merge options
                                 {:state state
                                  :looping? looping?
                                  :on-close on-close-fn
                                  :setup-fn setup-fn
                                  :settings-fn settings-fn
                                  :draw-fn draw-fn
                                  :osc-event osc-event-fn ;; ****************
                                  :renderer renderer
                                  :size size
                                  :display (:display options)
                                  :target-frame-rate (atom 60)
                                  :key-event (atom nil)}
                                 listeners)
        prx-obj           (quil.Applet. applet-state)]
    (doto prx-obj
      (applet-run title renderer)
      (attach-applet-listeners))))

(defmacro defapplet
  "Define and start an applet and bind it to a var with the symbol
  app-name. If any of the options to the various callbacks are
  symbols, it wraps them in a call to var to ensure they aren't
  inlined and that redefinitions to the original fns are reflected in
  the visualisation. See applet for the available options."
  [app-name & opts]
  (letfn [(wrap [v]
            (if (symbol? v)
              ; It is possible that symbol points to non-fn var.
              ; For example it points to [300 300] which defines size
              ; In this case we should not wrap it with (var ...)
              `(if (fn? ~v) (var ~v) ~v)
              v))]
    `(def ~app-name (applet ~@(map wrap opts)))))
