(ns tofu-avoidance.core
  (:require [goog.dom :as dom]
            [goog.events :as events]
            [cljs.core.async :as async :refer [<! chan put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(def ^:const canvas-width 512)
(def ^:const canvas-height 384)

(def ^:const new-enemy-interval 3)

(def ^:const player-width 8)
(def ^:const player-height 8)
(def ^:const player-velocity 2)

(def ^:const enemy-width 16)
(def ^:const enemy-height 16)
(def ^:const enemy-acceleration 0.05)

;; ---------------------------------------------------------------------------
;; Canvas UI {{{

(def animation-frame
  (or (.-requestAnimationFrame js/window)
      (.-webkitRequestAnimationFrame js/window)
      (.-mozRequestAnimationFrame js/window)
      (.-oRequestAnimationFrame js/window)
      (.-msRequestAnimationFrame js/window)
      (fn [callback] (js/setTimeout callback 16))))

(defn init-canvas []
  (let [canvas (dom/getElement "app")
        ctx (.getContext canvas "2d")]
    {:canvas canvas
     :ctx ctx}))

(defn fill-style [ctx color]
  (set! (.-fillStyle ctx) color))

(defn font!
  ([ctx family size]
   (font! ctx family nil size))
  ([ctx family style size]
   (set! (.-font ctx) (str (if (empty? style) "" (str style " "))
                           size "pt '" family "'"))))

(defn text-align! [ctx align]
  (set! (.-textAlign ctx) (name align)))

(defn fill-text!
  ([ctx texts x y line-height]
   (doseq [[idx t] (map-indexed vector texts)
           :let [y (+ y (* idx line-height))]]
     (fill-text! ctx t x y)))
  ([ctx text x y]
   (.fillText ctx text x y)))

;; }}}

;; ---------------------------------------------------------------------------
;; Geometry logic {{{

(defn bottom-right [{:keys [x y w h]}]
  {:x (+ x w)
   :y (+ y h)})

(defn top-left [{:keys [x y]}]
  {:x x
   :y y})

(defn collision? [obj obj2]
  (let [br (bottom-right obj)
        tl (top-left obj)
        br2 (bottom-right obj2)
        tl2 (top-left obj2)]
    (and (< (:y tl) (:y br2))
         (< (:y tl2) (:y br))
         (< (:x tl) (:x br2))
         (< (:x tl2) (:x br)))))

;; }}}

;; ---------------------------------------------------------------------------
;; Event handling {{{

(def keycodes
  {37 :left
   38 :up
   39 :right
   40 :down
   13 :enter})

(def move-keys "Keys trigger movement" #{:left :up :right :down})
(def reset-key "Reset key" #{:enter})

(defn event->key [event]
  (get keycodes (.-keyCode event) :key-not-found))

(defn key-chan [event-type parse-event]
  (let [ch (chan)]
    (events/listen (.-body js/document)
                   event-type
                   #(put! ch (parse-event (event->key %))))
    ch))

(defn keydown-chan []
  (key-chan (.-KEYDOWN events/EventType) (fn [k] [:keydown k])))

(defn keyup-chan []
  (key-chan (.-KEYUP events/EventType) (fn [k] [:keyup k])))

(defn keypress-chan []
  (key-chan (.-KEYPRESS events/EventType) (fn [k] [:keypress k])))

(defn frame-process-chan []
  (let [ch (chan)]
    (go (animation-frame
          (fn lo []
            (animation-frame lo)
            (put! ch [:frame-process]))))
    ch))

;; }}}

;; ---------------------------------------------------------------------------
;; Player logic {{{

(defn init-player [x y]
  {:x x :y y :w player-width :h player-height :vx 0 :vy 0 :dead? nil})

(defn update-player [{:keys [x y vx vy] :as this} enemies]
  (let [new-player (merge this
                          {:x (+ x vx)
                           :y (+ y vy)})]
    (if (some #(collision? new-player %) enemies)
      (assoc new-player :dead? true)
      new-player)))

(defn on-event-player [{:keys [x y vx vy] :as this} evt v]
  (merge this
         {:vx (case v
                :left (if (= evt :keydown) (- player-velocity) 0)
                :right (if (= evt :keydown) player-velocity 0)
                vx)
          :vy (case v
                :up (if (= evt :keydown) (- player-velocity) 0)
                :down (if (= evt :keydown) player-velocity 0)
                vy)}))

(defn render-player! [ctx {:keys [x y w h dead?] :as this}]
  (fill-style ctx (if dead? "red" "blue"))
  (.fillRect ctx x y w h))

;; }}}

;; ---------------------------------------------------------------------------
;; Enemy logic {{{

(defn init-enemy [x y]
  {:x x :y y :w enemy-width :h enemy-height :vy 0.0 :ay enemy-acceleration})

(defn update-enemy [{:keys [y h vy ay] :as this}]
  (let [new-vy (+ vy ay)
        new-y (+ y new-vy)]
    (when (< (- new-y h) canvas-width)
      (merge this
             {:y new-y
              :vy new-vy}))))

(defn render-enemy! [ctx {:keys [x y w h] :as this}]
  (fill-style ctx "white")
  (.fillRect ctx x y w h))

;; }}}

;; ---------------------------------------------------------------------------
;; Game scene {{{

(defn init-game []
  {:scene :game
   :frame 0
   :player (init-player (/ (- canvas-width player-width) 2)
                        (/ (- canvas-height player-height) 2))
   :enemies []})

(defn update-game
  [{:keys [frame player enemies] :as world}]
  (let [new-frame (inc frame)
        new-enemies (->> (conj (mapv update-enemy enemies)
                               (when (zero? (rem new-frame new-enemy-interval))
                                 (init-enemy (rand-int (- canvas-width enemy-width))
                                             (- enemy-height))))
                         (remove nil?))
        new-player (update-player player new-enemies)]
    (merge world
           {:scene (if (:dead? new-player) :gameover :game)
            :frame new-frame
            :player new-player
            :enemies new-enemies})))

(defn event-game
  [{:keys [player] :as world} evt v]
  (assoc world :player (on-event-player player evt v)))

(defn render-game!
  [ctx {:keys [frame player enemies]}]
  (.clearRect ctx 0 0 canvas-width canvas-height)
  (render-player! ctx player)
  (doseq [enemy enemies] (render-enemy! ctx enemy)))

;; }}}

;; ---------------------------------------------------------------------------
;; Title scene {{{

(defn event-title [world evt v]
  (if (and (= evt :keypress) (reset-key v))
    (init-game)
    world))

(defn render-title! [ctx]
  (.clearRect ctx 0 0 canvas-width canvas-height)
  (fill-style ctx "white")
  (font! ctx "Arial" 12)
  (text-align! ctx :center)
  (fill-text! ctx ["Simple game just avoid tofu" "Press \"Enter\" to start game"]
              (/ canvas-width 2) (/ canvas-height 2) 16))

;; }}}

;; ---------------------------------------------------------------------------
;; Gameover scene {{{

(defn event-gameover [world evt v]
  (if (and (= evt :keypress) (reset-key v))
    (init-game)
    world))

(defn render-gameover!
  [ctx {:keys [frame player enemies] :as world}]
  (render-game! ctx world)
  (fill-style ctx "rgba(0, 0, 0, 0.5)")
  (.fillRect ctx 0 0 canvas-width canvas-height)
  (fill-style ctx "white")
  (font! ctx "Arial" 12)
  (text-align! ctx :center)
  (fill-text! ctx ["Game over" "Press \"Enter\" to restart game"]
              (/ canvas-width 2) (/ canvas-height 2) 16))

;; }}}

;; ---------------------------------------------------------------------------
;; World logic {{{

(defn init-world []
  {:scene :title})

(defn update-world [{:keys [scene] :as world}]
  (case scene
    :game (update-game world)
    world))

(defn event-world [{:keys [scene] :as world} evt v]
  (case scene
    :title (event-title world evt v)
    :game (event-game world evt v)
    :gameover (event-gameover world evt v)))

(defn render-world! [ctx {:keys [scene] :as world}]
  (case scene
    :title (render-title! ctx)
    :game (render-game! ctx world)
    :gameover (render-gameover! ctx world)))

;; }}}

(defn main-loop [events-chan render-chan]
  (go-loop [{:keys [scene] :as world} (init-world)]
           (let [[evt v] (<! events-chan)]
             (case evt
               :frame-process (let [new-world (update-world world)]
                                (put! render-chan new-world)
                                (recur new-world))
               :keydown (recur (event-world world :keydown v))
               :keyup (recur (event-world world :keyup v))
               :keypress (recur (event-world world :keypress v))
               (recur world)))))

(defn render-loop! [ch]
  (let [{:keys [canvas ctx]} (init-canvas)]
    (go-loop []
             (let [{:keys [scene] :as world} (<! ch)]
               (render-world! ctx world)
               (recur)))))

(defn init-events []
  (let [keydown (keydown-chan)
        keyup (keyup-chan)
        keypress (keypress-chan)
        frame-process (frame-process-chan)]
    (async/merge [keydown keyup keypress frame-process])))

(defn init []
  (let [events-chan (init-events)
        render-chan (chan)]
    (main-loop events-chan render-chan)
    (render-loop! render-chan)))

(set! (.-onload js/window) init)
