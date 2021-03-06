/*
 * This file is provided to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.basho.riak.client.operations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;

import com.basho.riak.client.IRiakObject;
import com.basho.riak.client.RiakException;
import com.basho.riak.client.RiakRetryFailedException;
import com.basho.riak.client.bucket.Bucket;
import com.basho.riak.client.bucket.DomainBucket;
import com.basho.riak.client.cap.ClobberMutation;
import com.basho.riak.client.cap.ConflictResolver;
import com.basho.riak.client.cap.Mutation;
import com.basho.riak.client.cap.Retrier;
import com.basho.riak.client.cap.UnresolvedConflictException;
import com.basho.riak.client.convert.ConversionException;
import com.basho.riak.client.convert.Converter;
import com.basho.riak.client.raw.RawClient;
import com.basho.riak.client.raw.RiakResponse;
import com.basho.riak.client.raw.StoreMeta;

/**
 * Stores a given object into riak always fetches first.
 * 
 * <p>
 * Use {@link Bucket#store(Object)} methods to create a store operation. Also
 * look at {@link DomainBucket#store(Object)}.
 * </p>
 * 
 * 
 * TODO Should fetch first be optional? What about the vclock if not?
 * 
 * @author russell
 * @see Bucket
 * @see DomainBucket
 */
public class StoreObject<T> implements RiakOperation<T> {

    private final RawClient client;
    private final String bucket;
    private final String key;

    private Retrier retrier;
    private Integer r;
    private Integer w;
    private Integer dw;
    private boolean returnBody = false;

    private Mutation<T> mutation;
    private ConflictResolver<T> resolver;
    private Converter<T> converter;

    /**
     * Create a new StoreObject operation for the object in <code>bucket</code>
     * at <code>key</code>.
     * <p>
     * Use {@link Bucket} to create a store operation.
     * </p>
     * 
     * @param client
     *            the RawClient to use
     * @param bucket
     *            location of data to store
     * @param key
     *            location of data to store
     * @param retrier
     *            the Retrier to use for this operation
     */
    public StoreObject(final RawClient client, String bucket, String key, final Retrier retrier) {
        this.client = client;
        this.bucket = bucket;
        this.key = key;
        this.retrier = retrier;
    }

    /**
     * Fetches data from <code>bucket/key</code>, if item exists it is converted
     * with {@link Converter} and any siblings resolved with
     * {@link ConflictResolver}. {@link Mutation} is applied to the result which
     * is then converted back to {@link IRiakObject} and stored with the
     * {@link RawClient}. If <code>returnBody</code> is true then the returned
     * result is treated like a fetch (converted, conflict resolved) and the
     * resultant object returned.
     * 
     * @return the result of the store if <code>returnBody</code> is
     *         <code>true</code>, <code>null</code> if <code>returnBody</code>
     *         is <code>false</code>
     * @throws RiakException
     */
    public T execute() throws RiakRetryFailedException, UnresolvedConflictException, ConversionException {
        // fetch, mutate, put
        Callable<RiakResponse> command = new Callable<RiakResponse>() {
            public RiakResponse call() throws Exception {
                if (r != null) {
                    return client.fetch(bucket, key, r);
                } else {
                    return client.fetch(bucket, key);
                }
            }
        };

        final RiakResponse ros = retrier.attempt(command);
        final Collection<T> siblings = new ArrayList<T>(ros.numberOfValues());

        for (IRiakObject o : ros) {
            siblings.add(converter.toDomain(o));
        }

        final T resolved = resolver.resolve(siblings);
        final T mutated = mutation.apply(resolved);
        final IRiakObject o = converter.fromDomain(mutated, ros.getVclock());

        final RiakResponse stored = retrier.attempt(new Callable<RiakResponse>() {
            public RiakResponse call() throws Exception {
                return client.store(o, generateStoreMeta());
            }
        });

        final Collection<T> storedSiblings = new ArrayList<T>(stored.numberOfValues());

        for (IRiakObject s : stored) {
            storedSiblings.add(converter.toDomain(s));
        }

        return resolver.resolve(storedSiblings);
    }

    /**
     * Create a {@link StoreMeta} instance from this operations <code>w/dw/returnBody</code> params.
     * @return a {@link StoreMeta} populated with <code>w</code>, <code>dw</code> and <code>returnBody</code>
     */
    private StoreMeta generateStoreMeta() {
        return new StoreMeta(w, dw, returnBody);
    }

    /**
     * A store performs a fetch first (to get a vclock and resolve any conflicts), set the read quorum for the fetch
     *
     * @param r the read quorum for the pre-store fetch
     * @return this
     */
    public StoreObject<T> r(Integer r) {
        this.r = r;
        return this;
    }

    /**
     * Set the write quorum for the store operation
     * @param w
     * @return this
     */
    public StoreObject<T> w(Integer w) {
        this.w = w;
        return this;
    }

    /**
     * The durable write quorum for this store operation
     * @param dw
     * @return this
     */
    public StoreObject<T> dw(Integer dw) {
        this.dw = dw;
        return this;
    }

    /**
     * Should the store operation return a response body?
     * @param returnBody
     * @return this
     */
    public StoreObject<T> returnBody(boolean returnBody) {
        this.returnBody = returnBody;
        return this;
    }

    /**
     * The {@link Retrier} to use for the fetch and store operations.
     * @param retrier a {@link Retrier}
     * @return this
     */
    public StoreObject<T> retrier(final Retrier retrier) {
        this.retrier = retrier;
        return this;
    }

    /**
     * The {@link Mutation} to apply to the value retrieved from the fetch operation
     * @param mutation a {@link Mutation}
     * @return this
     */
    public StoreObject<T> withMutator(Mutation<T> mutation) {
        this.mutation = mutation;
        return this;
    }

    /**
     * The {@link ConflictResolver} to use on any sibling results returned from the fetch (and store if <code>returnBody</code> is true)
     * NOTE: since it is used for fetch and after store must be reusable.
     * @param resolver a {@link ConflictResolver}
     * @return this
     */
    public StoreObject<T> withResolver(ConflictResolver<T> resolver) {
        this.resolver = resolver;
        return this;
    }

    /**
     * The {@link Converter} to use
     * @param converter a {@link Converter}
     * @return this
     */
    public StoreObject<T> withConverter(Converter<T> converter) {
        this.converter = converter;
        return this;
    }

    /**
     * Creates a {@link ClobberMutation} that applies <code>value</code>
     * 
     * @param value
     *            new value
     * @return this StoreObject
     */
    public StoreObject<T> withValue(final T value) {
        this.mutation = new ClobberMutation<T>(value);
        return this;
    }
}
