/*
 * Copyright 2016 Real Logic Ltd.
 *
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
package uk.co.real_logic.agrona.concurrent.errors;

import uk.co.real_logic.agrona.collections.ArrayUtil;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.EpochClock;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_INT;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_LONG;
import static uk.co.real_logic.agrona.BitUtil.align;

/**
 * Distinct record of error observations. Rather than grow a record indefinitely when many errors of the same type
 * are logged, this log takes the approach of only recording distinct errors of the same type type and stack trace
 * and keeping a count and time of observation so that the record only grows with new distinct observations.
 *
 * The provided {@link AtomicBuffer} can wrap a memory-mapped file so logging can be out of process. This provides
 * the benefit that if a crash or lockup occurs then the log can be read externally without loss of data.
 *
 * This class is threadsafe to be used from multiple logging threads.
 *
 * The error records are recorded to the memory mapped buffer in the following format.
 *
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |R|                         Length                              |
 *  +-+-------------------------------------------------------------+
 *  |R|                     Observation Count                       |
 *  +-+-------------------------------------------------------------+
 *  |R|                Last Observation Timestamp                   |
 *  |                                                               |
 *  +-+-------------------------------------------------------------+
 *  |R|               First Observation Timestamp                   |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                     UTF-8 Encoded Error                      ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 * </pre>
 */
public class DistinctErrorLog
{
    /**
     * Offset within a record at which the length field begins.
     */
    public static final int LENGTH_OFFSET = 0;

    /**
     * Offset within a record at which the observation count field begins.
     */
    public static final int OBSERVATION_COUNT_OFFSET = SIZE_OF_INT;

    /**
     * Offset within a record at which the last observation timestamp field begins.
     */
    public static final int LAST_OBSERVATION_TIMESTAMP_OFFSET = OBSERVATION_COUNT_OFFSET + SIZE_OF_INT;

    /**
     * Offset within a record at which the first observation timestamp field begins.
     */
    public static final int FIRST_OBSERVATION_TIMESTAMP_OFFSET = LAST_OBSERVATION_TIMESTAMP_OFFSET + SIZE_OF_LONG;

    /**
     * Offset within a record at which the encoded exception field begins.
     */
    public static final int ENCODED_ERROR_OFFSET = FIRST_OBSERVATION_TIMESTAMP_OFFSET + SIZE_OF_LONG;

    /**
     * Alignment to be applied for record beginning.
     */
    public static final int RECORD_ALIGNMENT = SIZE_OF_LONG;

    private static final DistinctObservation INSUFFICIENT_SPACE = new DistinctObservation(null, 0);

    private int nextOffset = 0;
    private final EpochClock clock;
    private final AtomicBuffer buffer;
    private volatile DistinctObservation[] distinctObservations = new DistinctObservation[0];
    private final Lock lock = new ReentrantLock();

    /**
     * Create a new error log that will be written to a provided {@link AtomicBuffer}.
     *
     * @param buffer into which the observation records are recorded.
     * @param clock  to be used for time stamping records.
     */
    public DistinctErrorLog(final AtomicBuffer buffer, final EpochClock clock)
    {
        buffer.verifyAlignment();
        this.clock = clock;
        this.buffer = buffer;
    }

    /**
     * Record an observation of an error. If it is the first observation of this error type for a stack trace
     * then a new entry will be created. For subsequent observations of the same error type and stack trace a
     * counter and time of last observation will be updated.
     *
     * @param observation to be logged as an error observation.
     * @return true if successfully logged otherwise false if insufficient space remaining in the log.
     */
    public boolean record(final Throwable observation)
    {
        final long timestamp = clock.time();
        final DistinctObservation[] existingObservations = distinctObservations;
        DistinctObservation existingObservation = find(existingObservations, observation);

        if (null == existingObservation)
        {
            lock.lock();
            try
            {
                existingObservation = newObservation(timestamp, existingObservations, observation);
                if (INSUFFICIENT_SPACE == existingObservation)
                {
                    return false;
                }
            }
            finally
            {
                lock.unlock();
            }
        }

        final int offset = existingObservation.offset;
        buffer.getAndAddInt(offset + OBSERVATION_COUNT_OFFSET, 1);
        buffer.putLongOrdered(offset + LAST_OBSERVATION_TIMESTAMP_OFFSET, timestamp);

        return true;
    }

    private static DistinctObservation find(final DistinctObservation[] existingObservations, final Throwable observation)
    {
        DistinctObservation existingObservation = null;

        for (final DistinctObservation o : existingObservations)
        {
            if (equals(o.throwable, observation))
            {
                existingObservation = o;
                break;
            }
        }

        return existingObservation;
    }

    private static boolean equals(Throwable lhs, Throwable rhs)
    {
        while (true)
        {
            if (lhs == rhs)
            {
                return true;
            }

            if (lhs.getClass().equals(rhs.getClass()) && equals(lhs.getStackTrace(), rhs.getStackTrace()))
            {
                lhs = lhs.getCause();
                rhs = rhs.getCause();

                if (null == lhs && null == rhs)
                {
                    return true;
                }
                else if (null != lhs && null != rhs)
                {
                    continue;
                }
            }

            return false;
        }
    }

    private static boolean equals(final StackTraceElement[] lhsStackTrace, final StackTraceElement[] rhsStackTrace)
    {
        if (lhsStackTrace.length != rhsStackTrace.length)
        {
            return false;
        }

        for (int i = 0, length = lhsStackTrace.length; i < length; i++)
        {
            final StackTraceElement lhs = lhsStackTrace[i];
            final StackTraceElement rhs = rhsStackTrace[i];

            if (lhs.getLineNumber() != rhs.getLineNumber() ||
                !lhs.getClassName().equals(rhs.getClassName()) ||
                !Objects.equals(lhs.getMethodName(), rhs.getMethodName()) ||
                !Objects.equals(lhs.getFileName(), rhs.getFileName()))
            {
                return false;
            }
        }

        return true;
    }

    private DistinctObservation newObservation(
        final long timestamp, final DistinctObservation[] existingObservations, final Throwable observation)
    {
        DistinctObservation existingObservation = null;

        if (existingObservations != distinctObservations)
        {
            existingObservation = find(distinctObservations, observation);
        }

        if (null == existingObservation)
        {
            final StringWriter stringWriter = new StringWriter();
            observation.printStackTrace(new PrintWriter(stringWriter));
            final byte[] encodedError = stringWriter.toString().getBytes(UTF_8);

            final int length = ENCODED_ERROR_OFFSET + encodedError.length;
            final int offset = nextOffset;

            if ((offset + length) > buffer.capacity())
            {
                return INSUFFICIENT_SPACE;
            }

            buffer.putBytes(offset + ENCODED_ERROR_OFFSET, encodedError);
            buffer.putLong(offset + FIRST_OBSERVATION_TIMESTAMP_OFFSET, timestamp);
            nextOffset = align(offset + length, RECORD_ALIGNMENT);

            existingObservation = new DistinctObservation(observation, offset);
            distinctObservations = ArrayUtil.add(distinctObservations, existingObservation);

            buffer.putIntOrdered(offset + LENGTH_OFFSET, length);
        }

        return existingObservation;
    }

    public static final class DistinctObservation
    {
        public final Throwable throwable;
        public final int offset;

        public DistinctObservation(final Throwable throwable, final int offset)
        {
            this.throwable = throwable;
            this.offset = offset;
        }
    }
}
