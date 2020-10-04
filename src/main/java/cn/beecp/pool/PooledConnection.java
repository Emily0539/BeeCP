/*
 * Copyright Chris2018998
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
package cn.beecp.pool;

import cn.beecp.BeeDataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadPoolExecutor;

import static cn.beecp.util.BeeJdbcUtil.oclose;
import static java.lang.System.arraycopy;
import static java.lang.System.currentTimeMillis;

/**
 * Pooled Connection
 *
 * @author Chris.Liao
 * @version 1.0
 */
class PooledConnection {
    private static final boolean[] DEFAULT_IND = new boolean[6];
    private static Logger log = LoggerFactory.getLogger(PooledConnection.class);
    volatile int state;
    Connection rawConn;
    ProxyConnectionBase proxyConn;
    volatile long lastAccessTime;
    boolean commitDirtyInd;
    boolean curAutoCommit;
    boolean defaultAutoCommit;
    int defaultTransactionIsolationCode;
    boolean defaultReadOnly;
    String defaultCatalog;
    String defaultSchema;
    int defaultNetworkTimeout;
    private ThreadPoolExecutor defaultNetworkTimeoutExecutor;
    private FastConnectionPool pool;

    int tracedPos;
    boolean traceStatement;
    private ProxyStatementBase[] tracedStatements;
    private int resetCnt;// reset count
    private boolean[] resetInd = new boolean[DEFAULT_IND.length];

    public PooledConnection(Connection rawConn, int connState, FastConnectionPool connPool, BeeDataSourceConfig config) throws SQLException {
        pool = connPool;
        state = connState;
        this.rawConn = rawConn;

        //default value
        defaultTransactionIsolationCode = config.getDefaultTransactionIsolationCode();
        defaultReadOnly = config.isDefaultReadOnly();
        defaultCatalog = config.getDefaultCatalog();
        defaultSchema = config.getDefaultSchema();
        defaultNetworkTimeout = pool.getNetworkTimeout();
        defaultNetworkTimeoutExecutor = pool.getNetworkTimeoutExecutor();
        defaultAutoCommit = config.isDefaultAutoCommit();
        curAutoCommit = defaultAutoCommit;
        //default value

        tracedStatements = new ProxyStatementBase[(traceStatement = config.isTraceStatement()) ? 10 : 0];
        lastAccessTime = currentTimeMillis();//start time
    }

    /************* statement Operation ******************************/
    synchronized final void registerStatement(ProxyStatementBase st) {
        if (tracedPos == tracedStatements.length) {
            ProxyStatementBase[] stArray = new ProxyStatementBase[tracedPos << 1];
            System.arraycopy(tracedStatements, 0, stArray, 0, tracedPos);
            tracedStatements = stArray;
        }
        tracedStatements[tracedPos++] = st;
    }

    synchronized final void unregisterStatement(ProxyStatementBase st) {
        for (int i = 0; i < tracedPos; i++)
            if (st == tracedStatements[i]) {
                int m = tracedPos - i - 1;
                if (m > 0) System.arraycopy(tracedStatements, i + 1, tracedStatements, i, m);//move to ahead
                tracedStatements[--tracedPos] = null; // clear to let GC do its work
                return;
            }
    }
     final void cleanTracedStatements() {
        for (int i = 0; i < tracedPos; i++) {
            tracedStatements[i].setAsClosed();
            tracedStatements[i] = null;// clear to let GC do its work
        }
        tracedPos = 0;
    }

    /************* statement Operation ******************************/

    //close raw connection
    void closeRawConn() {//called by pool
        try {
            resetRawConnOnReturn();
        } catch (SQLException e) {
            log.error("Connection close error", e);
        } finally {
            oclose(rawConn);
        }
    }

    //***************called by connection proxy ********//
    final void recycleSelf() throws SQLException {
        try {
            proxyConn = null;
            resetRawConnOnReturn();
            pool.recycle(this);
        } catch (SQLException e) {
            pool.abandonOnReturn(this);
            throw e;
        }
    }

    final void updateAccessTime() {//for update,insert.select,delete and so on DML
        commitDirtyInd = !curAutoCommit;
        lastAccessTime = currentTimeMillis();
    }

    final void setResetInd(int p, boolean chgd) {
        if (!resetInd[p] && chgd)//false ->true       +1
            resetCnt++;
        else if (resetInd[p] && !chgd)//true-->false  -1
            resetCnt--;
        resetInd[p] = chgd;
        //lastAccessTime=currentTimeMillis();
    }

    boolean isSupportValidTest() {
        return pool.isSupportValidTest();
    }

    boolean isSupportSchema() {
        return pool.isSupportSchema();
    }

    boolean isSupportNetworkTimeout() {
        return pool.isSupportNetworkTimeout();
    }

    final void resetRawConnOnReturn() throws SQLException {
        if (!curAutoCommit && commitDirtyInd) { //Roll back when commit dirty
            rawConn.rollback();
            commitDirtyInd = false;
        }

        //reset begin
        if (resetCnt > 0) {
            if (resetInd[0]) {//reset autoCommit
                rawConn.setAutoCommit(defaultAutoCommit);
                curAutoCommit = defaultAutoCommit;
            }
            if (resetInd[1])
                rawConn.setTransactionIsolation(defaultTransactionIsolationCode);
            if (resetInd[2]) //reset readonly
                rawConn.setReadOnly(defaultReadOnly);
            if (resetInd[3]) //reset catalog
                rawConn.setCatalog(defaultCatalog);

            //for JDK1.7 begin
            if (resetInd[4]) //reset schema
                rawConn.setSchema(defaultSchema);
            if (resetInd[5]) //reset networkTimeout
                rawConn.setNetworkTimeout(defaultNetworkTimeoutExecutor, defaultNetworkTimeout);
            //for JDK1.7 end

            resetCnt = 0;
            arraycopy(DEFAULT_IND, 0, resetInd, 0, 6);
        }//reset end

        //clear warnings
        rawConn.clearWarnings();
    }
}