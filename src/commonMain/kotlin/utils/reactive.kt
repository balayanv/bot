package util

/**
 * This is a simplified light weight reactive streams alternative focused on simplicity, modularity and performance.
 * This is meant for declaratively defining high level flow of the program.
 *
 * There are few fundamental differences to "reactive manifesto" and rxjava.
 *
 * 1. Streams are infinite, so there is no onComplete(). If you need to iterate over a collection
 * you shouldn't be using rxjava or this library, there is java streams and kotlin sequences api which are great.
 * Admittedly when reactive streams were created those apis did not exist.
 *
 * 2. Errors are values and important part of the stream, so there is no onError(),
 * throwing an exception in a publisher or subscriber is not acceptable (implementations should report and ignore it).
 * If a stream is capable of communicating errors it should reflect that in the type of the stream.
 *
 * 3. Streams are not immutable. Rxjava streams are immutable, which creates awkward situation with users who find out
 * that their expensive .map.filter.whatever operations are duplicated and running multiple times on the same immutable messages.
 * Immutability is not necessary for declarative programming. So we dont enforce it.
 *
 * 4. Streams are not thread safe by default. Thread safety can be achieved in many ways often far more efficient than what rxjava does.
 * Instead each operator/high order function should serve a very specific purpose. Individual publisher or subscribers should opt int to be thread safe.
 */


// =============================================================================================
// main interfaces
// =============================================================================================

interface ISubscriber<A> {
   fun accept(element: A)
}

interface IObservable<A> {
   fun subscribe(subscriber: ISubscriber<A>)
   fun unsubscribe(subscriber: ISubscriber<A>)
   fun numberOfSubscribers(): Int
}

interface IObserver<A> {
   fun emit(element: A)
}

interface ISubject<A> : IObservable<A>, IObserver<A>


// =============================================================================================
// subscriber implementations
// =============================================================================================


open class UnsafeSubscriber<A>(private val onNext: (A) -> Unit) : ISubscriber<A> {
   override fun accept(element: A) {
      runIfFailsLogAndIgnore { onNext(element) }
   }
}

// subject implementations

/**
 * Simple subject, only has one job, keep track of subscribers in the order they subscribe.
 * Not thread safe.
 */
open class UnsafeSubject<A> : ISubject<A> {
   protected val subscribers = ArrayList<ISubscriber<A>>(1)

   override fun subscribe(subscriber: ISubscriber<A>) {
      subscribers.add(subscriber)
   }

   override fun unsubscribe(subscriber: ISubscriber<A>) {
      subscribers.remove(subscriber)
   }

   override fun numberOfSubscribers(): Int {
      return subscribers.size
   }

   override fun emit(element: A) {
      // while iterating any subscriber may add a new subscriber to this stream
      // we shall not have concurrent modification exception so iterate safely... using indexes
      for (i in 0 until subscribers.size) {
         val subscriber = subscribers[i]
         runIfFailsLogAndIgnore { subscriber.accept(element) }
      }
   }
}

/**
 * A subject which will buffer values up to a limit until a subscriber shows up.
 */
class AwaitingSubject<A>(private val bufferLimit: Int) : UnsafeSubject<A>() {
   private var buffer: ArrayDeque<A>? = ArrayDeque()

   override fun emit(element: A) {
      val buffer = this.buffer
      if (buffer == null) {
         super.emit(element)
      } else {
         if (buffer.size >= bufferLimit) {
            "awaiting subject buffer is full the oldest".logWarn()
            buffer.removeFirstOrNull()
         }
         buffer.add(element)
      }
   }

   override fun subscribe(subscriber: ISubscriber<A>) {
      super.subscribe(subscriber)
      val buffer = this.buffer
      if (buffer != null) {
         buffer.forEach { super.emit(it) }
         this.buffer = null
      }
   }
}

/**
 * Allows to join multiple observables under one subject,
 * once subscribed to it you will observe any emissions from any of the joined observables.
 */
open class JoiningSubject<A> : UnsafeSubject<A>() {
   fun join(other: IObservable<A>) {
      other.publishAllTo(this)
   }
}

/**
 * Unlike AwaitingSubject this one will preserve history of messages up to a given limit.
 */
class ReplayingSubject<A>(val limit: Int = 1024) : UnsafeSubject<A>() {
   private val observedValues = ArrayDeque<A>()

   override fun subscribe(subscriber: ISubscriber<A>) {
      observedValues.forEach { subscriber.accept(it) }
      super.subscribe(subscriber)
   }

   override fun emit(element: A) {
      observedValues.add(element)
      if (observedValues.size > limit) observedValues.removeFirst()
      super.emit(element)
   }
}


// =============================================================================================
// extensions
// =============================================================================================


fun <A> subscriber(onNext: (A) -> Unit): ISubscriber<A> = UnsafeSubscriber(onNext)

fun <A> subject(): ISubject<A> = UnsafeSubject()

fun <A> awaitingSubject(limit: Int): ISubject<A> = AwaitingSubject<A>(limit)

infix fun <A> IObservable<A>.subscribe(consumer: (A) -> Unit): ISubscriber<A> {
   val subscriber = subscriber(consumer)
   this.subscribe(subscriber)
   return subscriber
}

infix fun <A> IObservable<A>.doOnEach(consumer: (A) -> Unit): IObservable<A> {
   val subscriber = subscriber(consumer)
   this.subscribe(subscriber)
   return this
}

infix fun <A> IObservable<A>.forEach(consumer: (A) -> Unit) {
   this.subscribe(consumer)
}

infix fun <A> ISubscriber<A>.subscribeTo(source: IObservable<A>) {
   source.subscribe(this)
}

fun <T> IObservable<T>.hasNoSubscribers() = this.numberOfSubscribers() == 0

infix fun <A> A.publishTo(observer: IObserver<A>) {
   observer.emit(this)
}

fun <T> Sequence<T>.publishAllTo(source: IObserver<T>) {
   this.forEach { source.emit(it) }
}

fun <T> Collection<T>.publishAllTo(source: IObserver<T>) {
   this.forEach { source.emit(it) }
}

fun <A> IObservable<A>.publishAllTo(observer: IObserver<A>) {
   this.forEach { observer.emit(it) }
}

// =============================================================================================
// operators
// =============================================================================================

fun <A, B> IObservable<A>.map(function: (A) -> B): IObservable<B> {
   val sourceA = this
   val subjectB = subject<B>()
   sourceA.subscribe(subscriber { subjectB.emit(function.invoke(it)) })
   return subjectB
}

fun <A> IObservable<A>.filter(keep: (A) -> Boolean): IObservable<A> {
   val source = this
   val filtered = subject<A>()
   source.subscribe(subscriber {
      if (keep.invoke(it)) {
         filtered.emit(it)
      }
   })
   return filtered
}

fun <A, B> IObservable<A>.mapAndFilter(filter: (A) -> B?): IObservable<B> {
   val source = this
   val filtered = subject<B>()
   source.subscribe(subscriber {
      val mapped = filter.invoke(it)
      if (mapped != null) {
         filtered.emit(mapped)
      }
   })
   return filtered
}

fun <A> IObservable<A>.waitForFirstSubscriber(queueLimit: Int): ISubject<A> {
   val awaitingSubject = AwaitingSubject<A>(queueLimit)
   this.publishAllTo(awaitingSubject)
   return awaitingSubject
}

fun <A> IObservable<A>.replayForNewSubscribers(limit: Int): IObservable<A> {
   val replayingSubject = ReplayingSubject<A>(limit)
   this.publishAllTo(replayingSubject)
   return replayingSubject
}

fun <A> ISubject<A>.replayForNewSubscribers(limit: Int): ISubject<A> {
   val replayingSubject = ReplayingSubject<A>(limit)
   this.publishAllTo(replayingSubject)
   return replayingSubject
}

/**
 * Publish the pair of last values from each stream.
 */
fun <A, B> IObservable<A>.zipWith(other: IObservable<B>): IObservable<Pair<A, B>> {
   val stream1 = this
   val stream2 = other
   val result = subject<Pair<A, B>>()

   var lastA: A? = null
   var lastB: B? = null

   fun emitNext() {
      val a = lastA
      val b = lastB

      if (a != null && b != null) {
         result.emit(a to b)
      }
   }

   stream1.forEach {
      lastA = it
      emitNext()
   }

   stream2.forEach {
      lastB = it
      emitNext()
   }

   return result
}


/**
 * Create an observable which emits last observed values from list of observables of the same type.
 * All streams must emit at least once for this stream to publish anything.
 * Any new value will emit the array of last observed values.
 */
fun <A> List<IObservable<A>>.zip(): IObservable<List<A>> {
   val result = subject<List<A>>()
   val lastValues = arrayOfNulls<Any?>(this.size)

   // function for publishing a batch of last values, if all streams did emit at least once it will publish
   fun emitNext() {
      val batch = ArrayList<A>(lastValues.size)

      for (value in lastValues) {
         @Suppress("UNCHECKED_CAST")
         if (value != null) batch.add(value as A)
         else return
      }

      result.emit(batch)
   }

   // each stream will store its latest value and attempt to publish a batch
   for ((i, stream) in this.withIndex()) {
      stream.forEach {
         lastValues[i] = it
         emitNext()
      }
   }

   return result
}

fun <A> IObservable<A>.window(size: Int): IObservable<List<A>> {
   val stream = this
   val window = ArrayDeque<A>()
   val result = subject<List<A>>()

   stream.forEach {
      window.add(it)
      if (window.size > size) window.removeFirst()
      if (window.size == size) result.emit(window)
   }

   return result
}

fun <A, B> emitLastUnseenValuesOf(s1: IObservable<A>, s2: IObservable<B>): IObservable<Pair<A, B>> {
   val subject = subject<Pair<A, B>>()

   var lastS1: A? = null
   var lastS2: B? = null

   var newS1: A? = null
   var newS2: B? = null

   fun emitIfBothNew() {
      if (newS1 != lastS1 && newS2 != lastS2) {
         subject.emit(Pair(newS1!!, newS2!!))
         lastS1 = newS1
         lastS2 = newS2
      }
   }

   s1.forEach {
      newS1 = it
      emitIfBothNew()
   }

   s2.forEach {
      newS2 = it
      emitIfBothNew()
   }

   return subject
}

fun <A, B> emitPairFirstHappensBeforeSecond(s1: IObservable<A>, s2: IObservable<B>): IObservable<Pair<A, B>> {
   val subject = subject<Pair<A, B>>()

   var newS1: A? = null
   var newS2: B? = null

   fun emitIfBothNew() {
      if (newS1 != null && newS2 != null) {
         subject.emit(Pair(newS1!!, newS2!!))
         newS1 = null
         newS2 = null
      }
   }

   s1.forEach {
      newS1 = it
   }

   s2.forEach {
      if (newS1 != null) {
         newS2 = it
      }
      emitIfBothNew()
   }

   return subject
}

fun <A> IObservable<A>.join(that: IObservable<A>): IObservable<A> {
   if (this is JoiningSubject<A>) {
      this.join(that)
      return this
   } else {
      val joiningSubject = JoiningSubject<A>()
      joiningSubject.join(this)
      joiningSubject.join(that)
      return joiningSubject
   }
}

fun <A> List<IObservable<A>>.mergeObservables(): IObservable<A> {
   val subject = JoiningSubject<A>()

   for (observable in this) {
      subject.join(observable)
   }

   return subject
}


// =============================================================================================
// error handling
// =============================================================================================

internal inline fun runIfFailsLogAndIgnore(function: () -> Unit) {
   try {
      function()
   } catch (e: Exception) {
      e.printStackTrace()
   }
}

internal fun String.logWarn() {
   println("WARN: $this")
}
