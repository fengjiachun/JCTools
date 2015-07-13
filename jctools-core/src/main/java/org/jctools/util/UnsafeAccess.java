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
package org.jctools.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Why should we resort to using Unsafe?<br>
 * <ol>
 * <li>To construct class fields which allow volatile/ordered/plain access: This requirement is covered by
 * {@link AtomicReferenceFieldUpdater} and similar but their performance is arguably worse than the DIY approach
 * (depending on JVM version) while Unsafe intrinsification is a far lesser challenge for JIT compilers.
 * <li>To construct flavors of {@link AtomicReferenceArray}.
 * <li>Other use cases exist but are not present in this library yet.
 * </ol>
 *
 * 使用unsafe来提供对 volatile/ordered/plain 的读或写
 *
 * 值得注意的是不要被sun.misc.Unsafe.java或unsafe.cpp中的源码迷惑, 那都是在JIT之前的实现, JIT之后会被替换成针对平台优化的版本.
 *
 * 比如volatile/ordered的写操作在unsafe.cpp中代码相同的, 但putOrderedXXX系列方法在[hotspot/src/share/vm/classfile/vmSymbols.hpp]的宏定义中,
 * JIT会根据对应method的 intrinsic id 生成特定的针对平台优化指令集, 优化后的实现在[hotspot/src/share/vm/opto/library_call.cpp]中,
 * 对ordered的写操作在JIT之前是同写volatile一样, 插入了StoreLoad和StoreStore屏障(x86下StoreLoad是一条lock addl 指令, StoreStore不需要),
 * 但是JIT之后就只有StoreStore了.
 * 
 * @author nitsanw
 * 
 */
public class UnsafeAccess {
    public static final boolean SUPPORTS_GET_AND_SET;
    public static final Unsafe UNSAFE;
    static {
        try {
            final Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            SUPPORTS_GET_AND_SET = false;
            throw new RuntimeException(e);
        }
        boolean getAndSetSupport = false;
        try {
            Unsafe.class.getMethod("getAndSetObject", Object.class, Long.TYPE,Object.class);
            getAndSetSupport = true;
        } catch (Exception e) {
        }
        SUPPORTS_GET_AND_SET = getAndSetSupport;
    }

}
