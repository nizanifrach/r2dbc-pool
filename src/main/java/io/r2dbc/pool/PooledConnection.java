/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.pool;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.Wrapped;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.pool.PooledRef;

/**
 * Pooled {@link Connection} implementation. Performs a cleanup on {@link #close()} if used transactionally.
 * <p>
 * TODO: Initially, connections are assumed to work in auto-commit mode. Do we need to inspect auto-commit and restore the auto-commit state?
 *
 * @author Mark Paluch
 */
final class PooledConnection implements Connection, Wrapped<Connection> {

    private final PooledRef<Connection> ref;

    private final Connection connection;

    private volatile boolean closed = false;

    private volatile boolean inTransaction = false;

    PooledConnection(PooledRef<Connection> ref) {
        this.ref = ref;
        this.connection = ref.poolable();
    }

    @Override
    public Mono<Void> beginTransaction() {
        assertNotClosed();
        return Mono.from(this.connection.beginTransaction()).doOnSubscribe(ignore -> this.inTransaction = true);
    }

    @Override
    public Mono<Void> close() {
        assertNotClosed();

        return Mono.defer(() -> {

            Mono<Void> cleanup = Mono.empty();
            if (this.inTransaction) {
                cleanup = rollbackTransaction().onErrorResume(throwable -> Mono.empty()).then();
            }

            return cleanup.doOnSubscribe(ignore -> this.closed = true).then(this.ref.release());
        });
    }

    @Override
    public Mono<Void> commitTransaction() {
        assertNotClosed();
        return Mono.from(this.connection.commitTransaction()).doOnSubscribe(ignore -> this.inTransaction = false);
    }

    @Override
    public Batch createBatch() {
        assertNotClosed();
        return this.connection.createBatch();
    }

    @Override
    public Publisher<Void> createSavepoint(String s) {
        assertNotClosed();
        return this.connection.createSavepoint(s);
    }

    @Override
    public Statement createStatement(String s) {
        assertNotClosed();
        return this.connection.createStatement(s);
    }

    @Override
    public Publisher<Void> releaseSavepoint(String s) {
        assertNotClosed();
        return this.connection.releaseSavepoint(s);
    }

    @Override
    public Mono<Void> rollbackTransaction() {
        return Mono.from(this.connection.rollbackTransaction()).doOnSubscribe(ignore -> this.inTransaction = false);
    }

    @Override
    public Mono<Void> rollbackTransactionToSavepoint(String s) {
        return Mono.from(this.connection.rollbackTransactionToSavepoint(s));
    }

    @Override
    public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
        assertNotClosed();
        return this.connection.setTransactionIsolationLevel(isolationLevel);
    }

    @Override
    public Connection unwrap() {
        return this.connection;
    }

    private void assertNotClosed() {
        if (this.closed) {
            throw new IllegalStateException("The connection is closed");
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(getClass().getSimpleName());
        sb.append("[");
        sb.append(this.connection.toString());
        sb.append("]");
        return sb.toString();
    }
}
