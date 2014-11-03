(ns tofu-avoidance.core
  (:require [goog.events :as events]
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
;; Canvas UI

(def animation-frame
  (or (.-requestAnimationFrame js/window)
      (.-webkitRequestAnimationFrame js/window)
      (.-mozRequestAnimationFrame js/window)
      (.-oRequestAnimationFrame js/window)
      (.-msRequestAnimationFrame js/window)
      (fn [callback] (js/setTimeout callback 16))))

(defn init-canvas []
  (let [canvas (.getElementById js/document "app")
        ctx (.getContext canvas "2d")]
    {:canvas canvas
     :ctx ctx}))

(defn fill-style [ctx color]
  (set! (.-fillStyle ctx) color))

;; ---------------------------------------------------------------------------
;; Geometry logic

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

;; ---------------------------------------------------------------------------
;; Event handling

(def keycodes
  {37 :left
   38 :up
   39 :right
   40 :down})

(def move-keys "Keys trigger movement" #{:left :up :right :down})

(defn event->key [event]
  (get keycodes (.-keyCode event) :key-not-found))

(defn key-chan [event-type parse-event]
  (let [ch (chan)]
    (events/listen (.-body js/document)
                   event-type
                   #(put! ch (parse-event (event->key %))))
    ch))

(defn keyup-chan []
  (key-chan (.-KEYUP events/EventType) (fn [k] [:keyup k])))

(defn keydown-chan []
  (key-chan (.-KEYDOWN events/EventType) (fn [k] [:keydown k])))

(defn frame-process-chan []
  (let [ch (chan)]
    (go (animation-frame
          (fn lo []
            (animation-frame lo)
            (put! ch [:frame-process]))))
    ch))

;; ---------------------------------------------------------------------------
;; Player logic

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

;; ---------------------------------------------------------------------------
;; Enemy logic

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

;; ---------------------------------------------------------------------------
;; Game logic

(defn init-world []
  {:frame 0
   :player (init-player (/ (- canvas-width player-width) 2)
                        (/ (- canvas-height player-height) 2))
   :enemies []})

(defn update-world
  [{:keys [frame player enemies] :as world}]
  (let [new-frame (inc frame)
        new-enemies (->> (conj (mapv update-enemy enemies)
                               (when (zero? (rem new-frame new-enemy-interval))
                                 (init-enemy (rand-int (- canvas-width enemy-width))
                                             (- enemy-height))))
                         (remove nil?))
        new-player (update-player player new-enemies)]
    (merge world
           {:frame new-frame
            :player new-player
            :enemies new-enemies})))

(defn reflect-event
  [{:keys [player] :as world} evt v]
  (assoc world :player (on-event-player player evt v)))

(defn main-loop [events-chan render-chan]
  (go-loop [world (init-world)]
           (let [[evt v] (<! events-chan)]
             (case evt
               :frame-process (let [new-world (update-world world)]
                                (put! render-chan new-world)
                                (recur new-world))
               :keydown (recur (reflect-event world :keydown v))
               :keyup (recur (reflect-event world :keyup v))
               (recur world)))))

(defn render-loop! [ch]
  (let [{:keys [canvas ctx]} (init-canvas)]
    (go-loop []
             (let [{:keys [frame player enemies] :as world} (<! ch)]
               (.clearRect ctx 0 0 canvas-width canvas-height)
               (render-player! ctx player)
               (doseq [enemy enemies] (render-enemy! ctx enemy))
               (recur)))))

(defn init-events []
  (let [keydown (keydown-chan)
        keyup (keyup-chan)
        frame-process (frame-process-chan)]
    (async/merge [keydown keyup frame-process])))

(defn init []
  (let [events-chan (init-events)
        render-chan (chan)]
    (main-loop events-chan render-chan)
    (render-loop! render-chan)))

(set! (.-onload js/window) init)
