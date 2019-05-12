(ns fexpr-test.core
  (:require [clojure.core.match :refer [match]]
            [clojure.core.async :as a])
  (:gen-class))

;; experiment customer http://www.dalnefre.com/wp/2011/11/fexpr-the-ultimate-lambda/
;; expressions are actors! they receive messages for eval-ing, matching, binding etc.

;; =============================================================================
;; ACTOR EMULATION LAYER (jump to line 125 for the actual article content)
;; =============================================================================

;; dirty but useful for our approximation
(defrecord Actor [description mbox])
;; one shot actors are used to interact with the host environment and simulate some stuff
;; we can send and receive to it from anywhere as an external mailbox
;; without a loop, avoids making an actor with a "Send me what you received" command....
(defrecord OneShotActor [mbox])

(defn actor? [v]
  (instance? Actor v))

(defn one-shot-actor? [v]
  (instance? OneShotActor v))

;; to avoid getting a weird soup of words :p
(defn log [& args]
  (locking *out* (apply println args)))

(defmacro SEND [actor msg]
  `(if-let [mbox# (:mbox ~actor)]
     (a/>! @mbox# ~msg)
     (throw (ex-info "SEND with nil mbox" {:actor ~actor :msg ~msg}))))

(defmacro RECV [actor]
  (let [line (-> &form meta)]
    `(if-let [mbox# (:mbox ~actor)]
       (a/<! @mbox#)
       (throw (ex-info "RECV with nil mbox" {:actor ~actor})))))

(defmacro BECOME [new-actor]
  `(let [new-actor#            ~new-actor
         new-actor-ch-to-skip# @(:mbox new-actor#)]
     ;; the new actor's mailbox is replaced with the current actor's
     (reset! (:mbox new-actor#) @(:mbox ~'SELF))
     ;; if the new actor was blocking on his new unused mailbox we tell it to skip an iter
     ;; so it can start reading on current actor mailbox
     (a/>! new-actor-ch-to-skip# ::skip)
     ;; we close that new unused mailbox
     (a/close! new-actor-ch-to-skip#)
     ;; the current actor's loop get exited, only the new actor loop will read
     ;; on the current actor's mailbox
     (reset! ~'__exit true)))

(defmacro SUICIDE! []
  `(do (reset! ~'__exit true)
       (when-let [ch# @(:mbox ~'SELF)]
         (a/close! ch#))))

;; dirty unhygienic actor macros
(declare debug!)
(defmacro ACTOR [description & body]
  `(let [~'SELF   (Actor. ~description (atom (a/chan 256)))
         ~'__exit (atom false)]
     (a/go-loop [msg# (RECV ~'SELF)]
       (try
         (a/<! (debug! ~'SELF msg#))
         (match msg#
                ~@body
                ::skip      nil
                ::die       (SUICIDE!)
                [next# ~'_] (SEND next# [~'SELF [:not-understood msg#]])
                ~'_         (throw (ex-info "message not understand and no customer" {:msg msg#})))
         (catch Exception e#
           (log "ACTOR CRASH!\nmsg: " msg# "\nexception: " e#)
           (SUICIDE!)))
       (when-not @~'__exit (recur (RECV ~'SELF))))
     ~'SELF))

(defn one-shot-actor []
  (OneShotActor. (atom (a/chan 1))))

;; =========================
;; debugging/displaying utilities
;; =========================

(defn actor-to-string [actor]
  (a/go
    (if (actor? actor)
      (let [receiver (one-shot-actor)]
        (SEND actor [receiver [:tostring]])
        (or (RECV receiver) "!!!nil!!!"))
      (str "!!!" (ex-info "Calling to-string on non actor!" {:parameter actor}) "!!!")
      #_(throw (ex-info "Calling to-string on non actor!" {:parameter actor})))))

(defn msg-to-string [msg]
  (a/thread
    (str (clojure.walk/prewalk
          (fn [e] (cond
                    (actor? e) (a/<!! (actor-to-string e))
                    (one-shot-actor? e) "<<one-shot-actor>>"
                    :else e))
          msg))))

(defn substitute [msg target with-this]
  (clojure.walk/prewalk (fn [e] (if (= target e) with-this e)) msg))

(def order (atom 0))
(defn debug! [actor msg]
  (a/go
    (match
     msg
     ;; we hide all the message passing just for "tostring" purpose
     [_ [:tostring]]    nil
     :else (let [order'  (swap! order inc)
                 ;; substitution to avoid loops that would deadlock
                 msg-str (a/<! (msg-to-string
                                (substitute msg actor "<<SELF>>")))]
             (a/go
               #_(log (str "RAW >> " order' ": " (:description actor) " <- " msg-str))
               (log (str order' ": " (a/<! (actor-to-string actor)) " <- " msg-str)))))))


;; =============================================================================
;; THE ARTICLE
;; =============================================================================

(defn make-symbol [s]
  (ACTOR
   (str "Symbol " s)
   ;; symbol evals to a lookup in an environment provided by the request
   [customer [:eval env]] (SEND env [customer [:lookup SELF]])
   ;; the match messages is received when a parameter tree wants to match an expression
   ;; like (let [[a b] [1 2]] ...) in clojure, we defined new bindings in the environment
   ;; in our case, since we are a symbol it's simple
   [customer [:match value env]] (SEND env [customer [:bind SELF value]])
   [customer [:tostring]] (SEND customer (str s))))

(def Inert
  ;; does nothing but "be" (itSELF)
  (ACTOR
   "Inert"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "Inert")))

(def Ignore
  ;; Ignore is used in parameter trees to avoid bindings
  ;; pretty much _ in clojure
  (ACTOR
   "Ignore"
   [customer [:eval env]] (SEND env [customer [:lookup SELF]])
   ;; we return the standard binding answer but do not environment manipulation
   [customer [:match value env]]  (SEND customer Inert)
   [customer [:tostring]] (SEND customer "Ignore")))

(def Nil
  ;; empty list! also ~~the nil~~
  (ACTOR
   "Nil"
   [customer [:eval _]] (SEND customer SELF)
   ;; we return tuples as clojure list because it's easier to code :p
   [customer [:as-tuple]] (SEND customer (list SELF))
   ;; ~~~
   [customer [:map req-to-map]] (SEND SELF [customer req-to-map])
   ;; nil can only match to itself, and does not bind anything
   [customer [:match value env]] (if (= SELF value)
                                   (SEND customer Inert)
                                   (SEND customer [SELF [:bad-match value]]))
   [customer [:tostring]] (SEND customer "Nil")))

(defn msg-tag-and-forward [tag to]
  ;; any message that goes into this get tagged with the "actor"
  ;; we tag with a keyword instead of actor identity.... because it just makes
  ;; things simpler
  (ACTOR
   (str "Msg tag and forward: " tag)
   ;; we cannot tag and forward to-string messages.......
   ;; this is ugly but this is due to the ugly debug hack
   [customer [:tostring]] (SEND customer (str "<<forkjoin-tagforward[" tag "]>>"))
   msg (SEND to [tag msg])))

(defn forkjoin [receiver left right]
  ;; we implement this with actor because it gets easier to debug~~~
  (ACTOR
   "Forkjoin"
   [customer [:tostring]] (SEND customer "forkjoin-fork")
   [left-req right-req]
   (let [forkjoin1
         (ACTOR
          "Forkjoin1"
          [customer [:tostring]] (SEND customer "forkjoin[1]")
          [:left left-msg] (BECOME
                            (ACTOR
                             "Forkjoin2 got left needs right"
                             ;; an tostring message sent for forkjoin1 might end up here
                             [customer [:tostring]] (SEND customer "forkjoin[1]or[2-with-left]")
                             [:right right-msg] (do (SEND receiver [left-msg right-msg])
                                                    #_(SUICIDE!))))
          [:right right-msg] (BECOME
                              (ACTOR
                               "Forkjoin2 got right needs left"
                               ;; an tostring message sent for forkjoin1 might end up here
                               [customer [:tostring]] (SEND customer "forkjoin[1]or[2-with-right]")
                               [:left left-msg] (do (SEND receiver [left-msg right-msg])
                                                    #_(SUICIDE!)))))]
     (SEND left [(msg-tag-and-forward :left forkjoin1) left-req])
     (SEND right [(msg-tag-and-forward :right forkjoin1) right-req]))))

(defn make-pair [left right]
  (ACTOR
   (str "Pair(" left "; " right ")")
   [customer [:eval env]]
   (let [receive-combiner (one-shot-actor)]
     ;; we ask to left to send its eval-ed value to k_combine
     (SEND left [receive-combiner [:eval env]])
     (a/go (let [the-combiner-in-left (RECV receive-combiner)]
             (if (actor? the-combiner-in-left)
               (SEND the-combiner-in-left [customer [:combine right env]])
               (SEND customer [:invalid-combiner the-combiner-in-left])))))
   ;; out to-string stuff
   [customer [:tostring]] (a/go (SEND customer (str "(" (a/<! (actor-to-string left))
                                                    "; " (a/<! (actor-to-string right)) ")")))
   ;; --- flatten the nested pairs
   [customer [:as-tuple]]
   (let [receive-tuple (one-shot-actor)]
     (SEND right [receive-tuple [:as-tuple]])
     (a/go (let [right-tuple (RECV receive-tuple)]
             (SEND customer (cons left right-tuple)))))
   ;; --- apply a request on nested pairs!
   [customer [:map req-to-map]]
   (let [receive-forkjoin-map (one-shot-actor)
         forkjoin-map         (forkjoin receive-forkjoin-map left right)]
     ;; we map on proper lists! so we propagate the map message
     (SEND forkjoin-map [req-to-map [:map req-to-map]])
     (a/go (let [res (RECV receive-forkjoin-map)]
             (match res
                    [head tail] (SEND customer (make-pair head tail))
                    _ (SEND customer [SELF [:not-understood res]])))))
   ;; ---
   [customer [:match value env]]
   (let [receive-forkjoin-match  (one-shot-actor)
         forkjoin-match          (forkjoin receive-forkjoin-match value value)]
     ;; we will send to "value" a match-left and a match-right message
     (SEND forkjoin-match [[:match-left left env] [:match-right right env]])
     (a/go (let [matched (RECV receive-forkjoin-match)]
             (if (= [Inert Inert] matched)
               (SEND customer Inert)
               (SEND customer [SELF [:bad-match matched]])))))
   ;; ---
   ;; when we receive match-left and match-right it means WE are the value
   ;; of a [:match value env] message, and [:match-left left/right env]
   ;; of a ptree were sent to us!
   ;; so we ask to the ptree to match to the corresponding value!
   [customer [:match-left parameter-tree env]] (SEND parameter-tree [customer [:match left env]])
   [customer [:match-right parameter-tree env]] (SEND parameter-tree [customer [:match right env]])))

;; ===
;; Environments!
;; ===

(def Empty-env
  (ACTOR
   "Empty env"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "Ø")
   [customer [:lookup k]] (SEND customer [:undefined k])))

(defn make-env-scope [parent map]
  ;; environments are nested, which limits the effect of mutation to the current scope!
  ;; and allows shadowing of bindings
  (ACTOR
   "Env scope"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:lookup k]] (if-let [value (get map k)]
                            (SEND customer value)
                            (SEND parent [customer [:lookup k]]))
   [customer [:bind k value]] (do (SEND customer Inert)
                                  (BECOME (make-env-scope parent (assoc map k value))))
   [customer [:tostring]] (a/go (SEND customer (str "ENV[{..}" " " (a/<! (actor-to-string parent)) "]")))))

(defn make-new-scope [parent]
  (make-env-scope parent {}))

;; ===
;; Conditionals!
;; ===

(def Operative-&if
  (ACTOR
   "Operative &if"
   ;; evals to itSELF!
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "operative-if")
   ;; one of these things which can receive the combine message
   ;; applicative and operatives can!
   [customer [:combine operands env]]
   (let [receive-flattened-args (one-shot-actor)]
     ;; with pairs (a . b)
     ;; in this scheme (f a b c) is actually
     ;; (f . (a . (b . (c . ()))))
     ;; (and (f (a b)) -> (f . ((a . (b . ())) . ()))
     ;; lists are nested pairs ending in nil
     ;; so we want to flatten stuff
     (SEND operands [receive-flattened-args [:as-tuple]])
     (a/go (match (into [] (RECV receive-flattened-args))
                  [if-test then else NIL] (let [receive-boolean (one-shot-actor)]
                                            (SEND if-test [receive-boolean [:eval env]])
                                            ;; the true operand will eval then, and false eval else!
                                            (SEND (RECV receive-boolean)
                                                  [customer [:if then else env]]))
                  other (SEND customer [SELF [:syntax-error other]]))))))

(def True
  (ACTOR
   "True"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "True")
   [customer [:if then _ env]] (SEND then [customer [:eval env]])))

(def False
  (ACTOR
   "False"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "False")
   [customer [:if _ else env]] (SEND else [customer [:eval env]])))

;; ===
;; Mutability
;; ===

(def Operative-&define!
  (ACTOR
   "Operative &define!"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "operative-&define!")
   [customer [:combine operands env]]
   (let [receive-flattened-args (one-shot-actor)]
     (SEND operands [receive-flattened-args [:as-tuple]])
     (a/go (match (into [] (RECV receive-flattened-args))
                  [parameter-tree expr NIL]
                  (let [receive-evaled-expr (one-shot-actor)]
                    (SEND expr [receive-evaled-expr [:eval env]])
                    ;; the parameter tree will match with the evaled expression
                    ;; and potentially bind new symbols to the environment!
                    ;; in most cases the parameter tree will be a symbol....
                    (a/go (SEND parameter-tree
                                [customer [:match (RECV receive-evaled-expr) env]])))
                  other (SEND customer [SELF [:syntax-error customer other]]))))))

;; ===
;; Applicative
;; ===

(defn make-applicative [combiner]
  (ACTOR
   (str "Applicative " combiner)
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (a/go (SEND customer (str "APPLICATIVE[" (a/<! (actor-to-string combiner)) "]")))
   [customer [:combine operands env]]
   (let [receive-map-evaled (one-shot-actor)]
     (SEND operands [receive-map-evaled [:map [:eval env]]])
     (a/go (let [evaled-args (RECV receive-map-evaled)
                 expression  (make-pair combiner evaled-args)]
             (SEND expression [customer [:eval env]]))))
   [customer [:unwrap]] (SEND customer combiner)))

;; ===
;; Operatives
;; ===

(defn make-vau [static-env parameter-tree body]
  (ACTOR
   (str "Vau " static-env " " parameter-tree " " body " ")
   [customer [:eval _]] (SEND customer SELF)
   ;; TODO
   [customer [:tostring]] (a/go (SEND customer (str "VAU[static-env:" (a/<! (actor-to-string static-env))
                                                    " parameter-tree:" (a/<! (actor-to-string parameter-tree))
                                                    " body:" (a/<! (actor-to-string body)) "]")))
   [customer [:combine operands dynamic-env]]
   (let [local-env     (make-new-scope static-env)
         receive-match (one-shot-actor)]
     ;; operands are matched to the parameter tree unevaluated
     ;; so the local environment gets unevaluated expressions bound
     ;; and an extra binding for the dynamic environment!
     (SEND parameter-tree [receive-match
                           [:match (make-pair operands dynamic-env) local-env]])
     (a/go (let [matched (RECV receive-match)]
             (if (= Inert matched)
               ;; it will be up to the body to eval operands or not
               ;; and they might be eval-ed in the local env
               ;; or in the dynamic env
               (SEND body [customer [:eval local-env]])
               (SEND customer [SELF [:bad-match matched]])))))))

(def Operative-&vau
  (ACTOR
   "Operative &vau"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "operative-vau")
   ;; returns a Vau combiner to customer
   [customer [:combine operands static-env]]
   (let [receive-flattened-args (one-shot-actor)]
     (SEND operands [receive-flattened-args [:as-tuple]])
     (a/go (let [flattened-args (RECV receive-flattened-args)]
             (match (into [] flattened-args)
                    [vars env-var body NIL] (let [parameter-tree (make-pair vars env-var)
                                                  combiner       (make-vau static-env parameter-tree body)]
                                              (SEND customer combiner))
                    other (SEND customer [SELF [:bad-syntax other]])))))))

;; ===
;; Lambda
;; ===

;; we can implement lambda in term of vau!
;; we don't really care about the "optimized" version of the implementation

(def Operative-&lambda
  (ACTOR
   "Operative &lambda"
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer "operative-lambda")
   [customer [:combine operands static-env]]
   (let [receive-flattened-args (one-shot-actor)]
     (SEND operands [receive-flattened-args [:as-tuple]])
     (a/go (let [flattened-args (into [] (RECV receive-flattened-args))]
             (match flattened-args
                    [vars body NIL]
                    (let [parameter-tree (make-pair vars Ignore)
                          combiner       (make-vau static-env parameter-tree body)
                          ;; make applicative will evaluate all the operands
                          ;; in the static env!
                          applicative    (make-applicative combiner)]
                      (SEND customer applicative))
                    _ (SEND customer [SELF [:bad-syntax flattened-args]])))))))

;; ===
;; Identity
;; ===

(def Applicative-eq?
  (let [combiner (ACTOR
                  "Applicative eq?"
                  [customer [:eval _]] (SEND customer SELF)
                  [customer [:tostring]] (SEND customer "eq?-combiner")
                  [customer [:combine operands static-env]]
                  (let [receive-flattened-args (one-shot-actor)]
                    (SEND operands [receive-flattened-args [:as-tuple]])
                    (a/go (let [flattened-args (RECV receive-flattened-args)]
                            (match (into [] flattened-args)
                                   [x y NIL] (if (= x y)
                                               (SEND customer True)
                                               (SEND customer False))
                                   other (SEND customer [SELF [:bad-syntax other]]))))))]
    (make-applicative combiner)))

;; ===
;; Example Evaluation
;; ===

(defn make-constant [value]
  (ACTOR
   (str "Constant " value)
   [customer [:eval _]] (SEND customer SELF)
   [customer [:tostring]] (SEND customer (str value))))

(def Symbol-&if (make-symbol '&if))
(def Symbol-&define! (make-symbol '&define!))
(def Symbol-&vau (make-symbol '&vau))
(def Symbol-&lambda (make-symbol '&lambda))
(def Symbol-eq? (make-symbol 'eq?))
(def Ground-env (make-env-scope
                 Empty-env
                 {Symbol-&if      Operative-&if
                  Symbol-&define! Operative-&define!
                  Symbol-&vau     Operative-&vau
                  Symbol-&lambda  Operative-&lambda
                  Symbol-eq?      Applicative-eq?}))

;; no reason to make all the actors by hand....
(defn parse!
  ([expr symbols]
   (cond
     (nil? expr)     Nil
     (list? expr)    (if (empty? expr)
                       Nil
                       (make-pair (parse! (first expr) symbols)
                                  (parse! (rest expr) symbols)))
     (integer? expr) (make-constant expr)
     (symbol? expr)  (do (swap! symbols
                                (fn [symbols]
                                  (if (contains? symbols expr)
                                    symbols
                                    (assoc symbols expr (make-symbol expr)))))
                         (get @symbols expr))))
  ([expr]
   (parse! expr (atom {'&if      Symbol-&if
                       '&define! Symbol-&define!
                       '&vau     Symbol-&vau
                       '&lambda  Symbol-&lambda
                       'eq?      Symbol-eq?
                       '*inert   Inert
                       '*nil     Nil}))))

(defn eval! [expr env]
  (let [expr       (parse! expr)
        scoped-env (make-new-scope env)
        res        (one-shot-actor)]
    (a/<!! (a/go (SEND expr [res [:eval scoped-env]])
                 (RECV res)))))

(defn display [expr]
  (a/<!! (msg-to-string expr)))

(defn -main [& args]
  (log (display (eval! '((&lambda (x) (&if (eq? x *inert) answer x))
                         (&define! answer 42))
                       Ground-env))))
