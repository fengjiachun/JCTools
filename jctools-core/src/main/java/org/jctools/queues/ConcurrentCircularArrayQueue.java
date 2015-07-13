/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues;

import org.jctools.util.JvmInfo;
import org.jctools.util.Pow2;
import org.jctools.util.UnsafeAccess;

import java.util.AbstractQueue;
import java.util.Iterator;

import static org.jctools.util.UnsafeAccess.UNSAFE;

/**
 * {@link ConcurrentCircularArrayQueue} 左边的 pad
 * @param <E>
 */
abstract class ConcurrentCircularArrayQueueL0Pad<E> extends AbstractQueue<E> implements MessagePassingQueue<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p30, p31, p32, p33, p34, p35, p36, p37;
}

/**
 * A concurrent access enabling class used by circular array based queues this class exposes an offset computation
 * method along with differently memory fenced load/store methods into the underlying array. The class is pre-padded and
 * the array is padded on either side to help with False sharing prvention. It is expected theat subclasses handle post
 * padding.
 * <p>
 * Offset calculation is separate from access to enable the reuse of a give compute offset.
 * <p>
 * Load/Store methods using a <i>buffer</i> parameter are provided to allow the prevention of final field reload after a
 * LoadLoad barrier.
 * <p>
 * 这个类来自jctools, 我按照自己的理解做了一些轻微改动
 *
 * 这个Queue的数据结构是一个环形数组, 大概是下图这样:
 *
 *        productIndex:
 *        ==>==>==>==>==>==>==>==>==>==>==>==>==>...
 *                                                 )
 *        .........................................
 *       (
 *       ..==>==>==>
 * -------------------------------------------------------
 * |******| 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |******|
 * -------------------------------------------------------
 *        consumerIndex:
 *                      -->-->-->-->-->-->-->
 *
 * 前后的****部分为padding填充, 避免其他变量跟buffer在一个cache line中形成false sharing.
 * productIndex作为生产者索引沿着数组最小下标0向前直到抵达数组的最后一个元素index(这里为9),
 * 接着从buffer的0索引处重新开始(形成一个环)
 * consumerIndex同理, 当productIndex领先consumerIndex一圈(productIndex - buffer.capacity == consumerIndex)的时候表示队列已满.
 *
 *
 * 关于利用继承的方式进行缓存行填充:
 * 这里利用了对象继承另一个对象时的内存布局规则, 比如下面这个例子
 * ----------------------------------
 *  class A {
 *      byte a;
 *  }
 *
 *  class B {
 *      byte b;
 *  }
 *
 *  [HEADER:  8 bytes]  8
 *  [a:       1 byte ]  9
 *  [padding: 3 bytes] 12
 *  [b:       1 byte ] 13
 *  [padding: 3 bytes] 16
 * ----------------------------------
 *
 * 这个例子并不完全准确, 对象头并不一定是8个字节, 实际上是下面这个样子:
 * ---------------------------------------
 *  For 32 bit JVM:
 *      _mark   : 4 byte constant
 *      _klass  : 4 byte pointer to class
 *  For 64 bit JVM:
 *      _mark   : 8 byte constant
 *      _klass  : 8 byte pointer to class
 *  For 64 bit JVM with compressed-oops:
 *      _mark   : 8 byte constant
 *      _klass  : 4 byte pointer to class
 * ---------------------------------------
 *
 * 不考虑对象头的大小, A和B的内存布局已经足够说明问题,
 * 首先java中所有对象在内存中都按照8字节对齐, 不足8字节用padding补齐,
 * 其次父类A和子类B的内存布局是连续的, 并且有严格界限不会被打乱分配在内存里, 这样子类B的对象头再加上7个long padding占用大于等于64个字节的空间,
 * 这64个字节填充在ConcurrentCircularArrayQueue的左边, 保证不会有其他对象从左边与ConcurrentCircularArrayQueue形成false sharing.
 * 右边的padding的也是类似的处理方式.
 *
 * 以上讨论都是基于cache line大小为64个字节的CPU, 如果cache line为64个字节以上, 7个long的padding是不够的.
 * 
 * @author nitsanw
 * 
 * @param <E>
 */
public abstract class ConcurrentCircularArrayQueue<E> extends ConcurrentCircularArrayQueueL0Pad<E> {
    protected static final int SPARSE_SHIFT = Integer.getInteger("sparse.shift", 0);
    protected static final int REF_BUFFER_PAD; // REF_BUFFER_PAD * buffer中对象指针的size = 128
    private static final long REF_ARRAY_BASE; // buffer的base offset
    private static final int REF_ELEMENT_SHIFT;
    static {
        final int scale = UnsafeAccess.UNSAFE.arrayIndexScale(Object[].class);
        if (4 == scale) {
            REF_ELEMENT_SHIFT = 2 + SPARSE_SHIFT;
        } else if (8 == scale) {
            REF_ELEMENT_SHIFT = 3 + SPARSE_SHIFT;
        } else {
            throw new IllegalStateException("Unknown pointer size");
        }
        // 对buffer的padding, buffer的左右各加2个cache line大小的pad
        // 2 cache lines pad
        REF_BUFFER_PAD = (JvmInfo.CACHE_LINE_SIZE * 2) / scale;
        // Including the buffer pad in the array base offset
        REF_ARRAY_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(Object[].class) + (REF_BUFFER_PAD * scale);
    }
    protected final long mask;
    // @Stable :(
    protected final E[] buffer;

    @SuppressWarnings("unchecked")
    public ConcurrentCircularArrayQueue(int capacity) {
        // 强行设置容量为2的n次方, 是为了使用更高效的[按位与]代替[取模]
        int actualCapacity = Pow2.roundToPowerOfTwo(capacity);
        mask = actualCapacity - 1;
        // buffer的前后都加了REF_BUFFER_PAD个指针size的padding, disruptor也做了类似的改进
        // pad data on either end with some empty slots.
        buffer = (E[]) new Object[(actualCapacity << SPARSE_SHIFT) + REF_BUFFER_PAD * 2];
    }

    /**
     * 根据element index来计算此element在内存中相对buffer首地址的偏移量(offset)
     *
     * @param index desirable element index
     * @return the offset in bytes within the array for a given index.
     */
    protected final long calcElementOffset(long index) {
        return calcElementOffset(index, mask);
    }

    /**
     * 根据element index来计算此element在内存中相对buffer首地址的偏移量(offset)
     *
     * @param index desirable element index
     * @param mask 
     * @return the offset in bytes within the array for a given index.
     */
    protected static final long calcElementOffset(long index, long mask) {
        return REF_ARRAY_BASE + ((index & mask) << REF_ELEMENT_SHIFT);
    }

    /**
     * A plain store (no ordering/fences) of an element to a given offset
     *
     * 普通写, 不插入任何内存屏障, 不保证可见性
     * 
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e a kitty
     */
    protected final void spElement(long offset, E e) {
        spElement(buffer, offset, e);
    }

    /**
     * A plain store (no ordering/fences) of an element to a given offset
     *
     * 普通写, 不插入任何内存屏障, 不保证可见性
     * 
     * @param buffer this.buffer
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e an orderly kitty
     */
    protected static final <E> void spElement(E[] buffer, long offset, E e) {
        UNSAFE.putObject(buffer, offset, e);
    }

    /**
     * An ordered store(store + StoreStore barrier) of an element to a given offset
     *
     * ordered写, 会在前边插入一个StoreStore内存屏障, 保证不和前边的写操作重排序(事实上在x86上啥也没做), 也不保证可见性.
     *
     * 注意在UNSAFE.putOrderedObject方法被JIT编译之前, ordered写与volatile写具有相同的语义(即保证可见性), 会插入[StoreStore + StoreLoad],
     * 但被JIT编译后就没有没有StoreLoad屏障了, 具体参照 {@link UnsafeAccess} 中的注释
     * 
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e an orderly kitty
     */
    protected final void soElement(long offset, E e) {
        soElement(buffer, offset, e);
    }

    /**
     * An ordered store(store + StoreStore barrier) of an element to a given offset
     *
     * ordered写, 会在前边插入一个StoreStore内存屏障, 保证不和前边的写操作重排序(事实上在x86上啥也没做), 也不保证可见性.
     *
     * 注意在UNSAFE.putOrderedObject方法被JIT编译之前, ordered写与volatile写具有相同的语义(即保证可见性), 会插入[StoreStore + StoreLoad],
     * 但被JIT编译后就没有没有StoreLoad屏障了, 具体参照 {@link UnsafeAccess} 中的注释
     * 
     * @param buffer this.buffer
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e an orderly kitty
     */
    protected static final <E> void soElement(E[] buffer, long offset, E e) {
        UNSAFE.putOrderedObject(buffer, offset, e);
    }

    /**
     * A plain load (no ordering/fences) of an element from a given offset.
     *
     * 普通读, 不插入任何内存屏障.
     * 
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final E lpElement(long offset) {
        return lpElement(buffer, offset);
    }

    /**
     * A plain load (no ordering/fences) of an element from a given offset.
     *
     * 普通读, 不插入任何内存屏障.
     * 
     * @param buffer this.buffer
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    @SuppressWarnings("unchecked")
    protected static final <E> E lpElement(E[] buffer, long offset) {
        return (E) UNSAFE.getObject(buffer, offset);
    }

    /**
     * A volatile load (load + LoadLoad barrier) of an element from a given offset.
     *
     * volatile读, 会插入一个LoadLoad屏障(事实上在x86上仍然什么也没做)
     * 
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final E lvElement(long offset) {
        return lvElement(buffer, offset);
    }

    /**
     * A volatile load (load + LoadLoad barrier) of an element from a given offset.
     *
     * volatile读, 会插入一个LoadLoad屏障(事实上在x86上仍然什么也没做)
     * 
     * @param buffer this.buffer
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    @SuppressWarnings("unchecked")
    protected static final <E> E lvElement(E[] buffer, long offset) {
        return (E) UNSAFE.getObjectVolatile(buffer, offset);
    }

    @Override
    public Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public void clear() {
        while (poll() != null || !isEmpty())
            ;
    }
    
    public int capacity() {
        return (int) (mask + 1);
    }
}
