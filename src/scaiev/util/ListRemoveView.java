package scaiev.util;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Utility class: View of a {@link java.util.List} that supports O(1) removal through its iterator without touching the underlying List.
 * Other than removal, ListRemoveView behaves as a read-only List.
 *
 * Time complexity:
 * get(i) becomes O(inner.size())+O(inner.get(i)) due to slow index translation. remove(i) behaves like get(i).
 * Iteration becomes O(inner.size())*O(inner.get(i)), i.e. remains the same for {@link java.util.ArrayList}.
 * The remove method in {@link ListRemoveView#listIterator()} has a time complexity of O(1).
 */
public class ListRemoveView<E> implements List<E> {

  List<E> inner;
  boolean[] inner_removed;
  int num_inner_removed;
  ListRemoveView<E> alsoRemoveFrom = null;

  public ListRemoveView(List<E> inner) {
    this.inner = inner;
    this.inner_removed = new boolean[inner.size()];
    this.num_inner_removed = 0;
  }

  /** Sets a ListRemoveView where all removes to this object should be mirrored to. */
  private void setAlsoRemoveFrom(ListRemoveView<E> alsoRemoveFrom) {
    if (alsoRemoveFrom.inner != inner)
      throw new IllegalArgumentException("alsoRemoveFrom must refer to the same inner list");
    this.alsoRemoveFrom = alsoRemoveFrom;
  }

  /** Marks an element as removed, based on an index to the inner list. */
  protected void removeAbsIdx(int abs_idx) {
    if (!inner_removed[abs_idx]) {
      inner_removed[abs_idx] = true;
      ++num_inner_removed;
      if (alsoRemoveFrom != null) {
        alsoRemoveFrom.removeAbsIdx(abs_idx);
      }
    }
  }

  @Override
  public int size() {
    if (inner.size() != inner_removed.length)
      throw new IllegalStateException("The inner List of a ListRemoveView has changed in size");
    assert (inner.size() >= num_inner_removed);
    return inner.size() - num_inner_removed;
  }

  @Override
  public boolean isEmpty() {
    return size() <= 0;
  }

  @Override
  public boolean contains(Object o) {
    for (E obj : this)
      if (Objects.equals(obj, o))
        return true;
    return false;
  }

  @Override
  public Iterator<E> iterator() {
    return listIterator();
  }

  @Override
  public Object[] toArray() {
    Object[] arr = new Object[size()];
    int i = 0;
    for (E obj : this)
      arr[i++] = obj;
    return arr;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T[] toArray(T[] a) {
    Class<?> a_class = a.getClass().componentType();
    T[] arr = (a.length >= this.size()) ? a : (T[])Array.newInstance(a_class, size());
    int i = 0;
    for (E obj : this) {
      if (!(a_class.isInstance(obj))) {
        throw new ArrayStoreException();
      }
      arr[i++] = (T)obj;
    }
    return arr;
  }

  @Override
  public boolean add(E e) {
    throw new UnsupportedOperationException("ListRemoveView cannot add elements");
  }

  @Override
  public boolean remove(Object o) {
    for (int idx = 0; idx < inner.size(); ++idx) {
      if (!inner_removed[idx] && Objects.equals(inner.get(idx), o)) {
        removeAbsIdx(idx);
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    for (Object x : c) {
      if (!contains(x))
        return false;
    }
    return true;
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    throw new UnsupportedOperationException("ListRemoveView cannot add elements");
  }

  @Override
  public boolean addAll(int index, Collection<? extends E> c) {
    throw new UnsupportedOperationException("ListRemoveView cannot add elements");
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    boolean changed = false;
    for (Object x : c) {
      boolean removed = remove(x);
      changed = changed || removed;
    }
    return changed;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    boolean changed = false;
    for (int idx = 0; idx < inner.size(); ++idx) {
      if (!inner_removed[idx] && !c.contains(inner.get(idx))) {
        removeAbsIdx(idx);
        changed = true;
      }
    }
    return changed;
  }

  @Override
  public void clear() {
    for (int i = 0; i < inner.size(); ++i)
      removeAbsIdx(i);
  }

  @Override
  public E get(int index) {
    if (index < 0 || index >= size())
      throw new IndexOutOfBoundsException();
    if (num_inner_removed == 0)
      return inner.get(index);
    // Slow: Don't have any hints for index translation.
    int i = 0;
    for (E obj : this) {
      if (i == index)
        return obj;
      i++;
    }
    assert (false);
    return null;
  }

  @Override
  public E set(int index, E element) {
    throw new UnsupportedOperationException("ListRemoveView cannot set elements");
  }

  @Override
  public void add(int index, E element) {
    throw new UnsupportedOperationException("ListRemoveView cannot add elements");
  }

  /** Translation of an index in this list view to an index in the inner list. Returns -1 if out of range. */
  private int translateToInnerIndex(int index) {
    if (index < 0 || index > size())
      throw new IndexOutOfBoundsException();
    int abs_idx = 0;
    for (abs_idx = 0; abs_idx < inner.size(); ++abs_idx) {
      if (!inner_removed[abs_idx]) {
        if (index == 0)
          break;
        index--;
      }
    }
    assert (abs_idx < inner.size());
    return abs_idx;
  }

  @Override
  public E remove(int index) {
    if (index < 0 || index >= size())
      throw new IndexOutOfBoundsException();
    if (num_inner_removed == 0) {
      removeAbsIdx(index);
      return inner.get(index);
    }
    // Slow: Don't have any hints for index translation.
    int inner_idx = translateToInnerIndex(index);
    if (inner_idx != -1) {
      removeAbsIdx(inner_idx);
      return inner.get(inner_idx);
    }
    assert (false);
    return null;
  }

  @Override
  public int indexOf(Object o) {
    if (num_inner_removed == 0)
      return inner.indexOf(o);
    // Slow: Don't have any hints for index translation.
    int i = 0;
    for (E obj : this) {
      if (Objects.equals(obj, o))
        return i;
      i++;
    }
    return -1;
  }

  @Override
  public int lastIndexOf(Object o) {
    if (num_inner_removed == 0)
      return inner.lastIndexOf(o);
    // Slow: Don't have any hints for index translation.
    int ret = -1;
    int i = 0;
    for (E obj : this) {
      if (Objects.equals(obj, o))
        ret = i;
      i++;
    }
    return ret;
  }

  @Override
  public ListIterator<E> listIterator() {
    return listIterator(0);
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    if (index < 0 || index > size())
      throw new IndexOutOfBoundsException();
    int abs_idx_start = (index == size()) ? -1 : translateToInnerIndex(index);
    int abs_idx_start_ = (abs_idx_start == -1) ? inner.size() : abs_idx_start;
    return new ListIterator<E>() {
      int prev_idx;
      int idx = init(); //(hack: can't have an actual anonymous class constructor)
      int return_idx;
      private void searchPrev() {
        for (prev_idx = idx - 1; prev_idx >= 0; --prev_idx) {
          if (!inner_removed[prev_idx])
            break;
        }
      }
      private void searchNext() {
        for (idx = idx + 1; idx < inner.size(); ++idx) {
          if (!inner_removed[idx])
            break;
        }
      }
      private int init() {
        prev_idx = -1;
        idx = abs_idx_start_ - 1;
        return_idx = -1;
        searchNext();
        searchPrev();
        return idx;
      }
      @Override
      public boolean hasNext() {
        return idx < inner.size();
      }

      @Override
      public E next() {
        E ret = inner.get(idx);
        return_idx = idx;
        assert (!inner_removed[return_idx]);
        searchNext();
        searchPrev();
        return ret;
      }

      @Override
      public boolean hasPrevious() {
        return prev_idx >= 0;
      }

      @Override
      public E previous() {
        E ret = inner.get(prev_idx);
        return_idx = prev_idx;
        assert (!inner_removed[return_idx]);
        idx = prev_idx;
        searchPrev();
        return ret;
      }

      @Override
      public int nextIndex() {
        return idx;
      }

      @Override
      public int previousIndex() {
        return prev_idx;
      }

      @Override
      public void remove() {
        if (inner_removed[return_idx])
          throw new IllegalStateException("remove() called on an already-removed item");
        removeAbsIdx(return_idx);
      }

      @Override
      public void set(E e) {
        throw new UnsupportedOperationException("ListRemoveView cannot set elements");
      }

      @Override
      public void add(E e) {
        throw new UnsupportedOperationException("ListRemoveView cannot add elements");
      }
    };
  }

  @Override
  public List<E> subList(int fromIndex, int toIndex) {
    if (fromIndex >= size() || toIndex <= fromIndex)
      return List.of();

    // Create a copy of this object, with [0..fromIndex) and [toIndex..N] marked as removed.
    int fromIndexAbs = translateToInnerIndex(fromIndex);
    int toIndexAbs = translateToInnerIndex(toIndex);
    if (toIndex >= size())
      toIndexAbs = inner.size();

    ListRemoveView<E> ret = new ListRemoveView<E>(inner);
    int i = 0;
    Iterator<E> retIterator = ret.iterator();
    while (retIterator.hasNext()) {
      retIterator.next();
      if (i < fromIndexAbs || i >= toIndexAbs || this.inner_removed[i])
        retIterator.remove();
      ++i;
    }

    // From now on, any removes in the returned view will also affect this view.
    ret.setAlsoRemoveFrom(this);

    return ret;
  }
}
