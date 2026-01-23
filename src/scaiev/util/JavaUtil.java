package scaiev.util;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

public class JavaUtil {
  private static class ConcatIterator2<T> implements Iterator<T> {
    Iterator<T> a, b;
    public ConcatIterator2(Iterator<T> a, Iterator<T> b) {
      this.a = a;
      this.b = b;
    }

    @Override
    public boolean hasNext() {
      return a.hasNext() || (b != null && b.hasNext());
    }

    @Override
    public T next() {
      if (!a.hasNext() && b != null) {
        a = b;
        b = null;
      }
      // if !a.hasNext(), let its next() method throw an exception for us lazy folks
      return a.next();
    }
  }
  private static class ConcatIterator<T> implements Iterator<T> {
    Deque<Iterator<T>> iterators;
    public ConcatIterator(Collection<Iterator<T>> iterators) {
      this.iterators = new ArrayDeque<Iterator<T>>(iterators);
    }
    public ConcatIterator(Stream<Iterator<T>> iterators) {
      this.iterators = new ArrayDeque<Iterator<T>>();
      iterators.forEach(iter -> this.iterators.add(iter));
    }

    @Override
    public boolean hasNext() {
      return iterators.stream().filter(it->it.hasNext()).findFirst().orElse(null) != null;
    }

    @Override
    public T next() {
      while (!iterators.isEmpty() && !iterators.getFirst().hasNext())
        iterators.removeFirst();
      if (iterators.isEmpty())
        throw new NoSuchElementException();
      return iterators.getFirst().next();
    }
  }

  /**
   * Returns an Iterable that outputs only the first &lt;amount&gt; elements
   * @param <T> the element type
   * @param a the original Iterable
   * @param amount the number of elements to output
   * @return a new Iterable
   */
  public static <T> Iterable<T> iterableLimit(Iterable<T> a, int amount) {
    return new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        Iterator<T> ret = a.iterator();
        return new Iterator<T>() {
          int i = 0;
          @Override
          public boolean hasNext() {
            return i < amount && ret.hasNext();
          }

          @Override
          public T next() {
            if (i >= amount)
              throw new NoSuchElementException();
            ++i;
            return ret.next();
          }
        };
      }
    };
  }
  /**
   * Returns an Iterable that skips over the first &lt;skip&gt; elements
   * @param <T> the element type
   * @param a the original Iterable
   * @param skip the number of elements to skip
   * @return a new Iterable
   */
  public static <T> Iterable<T> iterableSkip(Iterable<T> a, int skip) {
    return new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        Iterator<T> ret = a.iterator();
        for (int i = 0; i < skip && ret.hasNext(); ++i)
          ret.next();
        return ret;
      }
    };
  }
  /**
   * Returns an Iterable that only outputs the element indices [skip,...,until-1]  
   * @param <T> the element type
   * @param a the original Iterable
   * @param skip the number of elements to skip
   * @param until the index to the first element not to output (w.r.t. the original Iterable a)
   * @return a new Iterable
   */
  public static <T> Iterable<T> iterableRange(Iterable<T> a, int skip, int until) {
    return iterableSkip(iterableLimit(a, until), skip);
  }
  /**
   * Produces an Iterator as a concatenation of the two given iterators.
   * @param <T> the element type
   * @param a the first Iterator
   * @param b the second Iterator
   * @return the concatenated Iterator
   */
  public static <T> Iterator<T> concatIterator(Iterator<T> a, Iterator<T> b) {
    return new ConcatIterator2<T>(a, b);
  }
  /**
   * Produces an Iterator as a concatenation of the given collection of iterators.
   * @param <T> the element type
   * @param iterators the iterators to concatenate
   * @return a concatenated Iterator
   */
  public static <T> Iterator<T> concatIterator(Collection<Iterator<T>> iterators) {
    return new ConcatIterator<T>(iterators);
  }
  /**
   * Produces an Iterator as a concatenation of the given vararg array of iterators.
   * @param <T> the element type
   * @param iterators the iterators to concatenate
   * @return a concatenated Iterator
   */
  @SafeVarargs
  public static <T> Iterator<T> concatIterator(Iterator<T>... iterators) {
    return new ConcatIterator<T>(Arrays.asList(iterators));
  }
  /**
   * Produces an Iterable as a concatenation of the two given iterables.
   * @param <T> the element type
   * @param a the first Iterable
   * @param b the second Iterable
   * @return the concatenated Iterable
   */
  public static <T> Iterable<T> concatIterable(Iterable<T> a, Iterable<T> b) {
    return new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new ConcatIterator2<T>(a.iterator(), b.iterator());
      }
    };
  }
  /**
   * Produces an Iterable as a concatenation of the given collection of iterables.
   * @param <T> the element type
   * @param iterables the iterables to concatenate
   * @return a concatenated Iterable
   */
  public static <T> Iterable<T> concatIterable(Collection<Iterable<T>> iterables) {
    return new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new ConcatIterator<T>(iterables.stream().map(iterab -> iterab.iterator()));
      }
    };
  }
  /**
   * Produces an Iterable as a concatenation of the given vararg array of iterables.
   * @param <T> the element type
   * @param iterables the iterables to concatenate
   * @return a concatenated Iterable
   */
  @SafeVarargs
  public static <T> Iterable<T> concatIterable(Iterable<T>... iterables) {
    return concatIterable(Arrays.asList(iterables));
  }
}
