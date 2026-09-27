package dev.plsql.spring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.call.CallExecutor;
import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.DictionarySignatureSource;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.PlsqlApiInvocationHandler;
import dev.plsql.spring.support.PlsqlExceptionTranslator;
import dev.plsql.spring.support.PlsqlRuntime;

/**
 * Creates implementations of {@link PlsqlApi} interfaces. Spring Boot users get one from the
 * auto-configuration; without Spring:
 *
 * <pre>
 * PlsqlApiFactory factory = PlsqlApiFactory.builder(dataSource)
 *         .argumentDefaults(ArgumentDefaults.byName(Map.of("NTENANT", ctx::tenant)))
 *         .build();
 * AppCore core = factory.create(AppCore.class);
 * </pre>
 *
 * {@link #create} reads every signature and checks every method before returning, so a
 * mismatch between the interface and the database fails here, with all reasons listed.
 */
public final class PlsqlApiFactory {

    private final PlsqlRuntime runtime;

    private PlsqlApiFactory(PlsqlRuntime runtime) {
        this.runtime = runtime;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    public <T> T create(Class<T> api) {
        return PlsqlApiInvocationHandler.create(api, runtime);
    }

    public PlsqlRuntime runtime() {
        return runtime;
    }

    public static final class Builder {
        private final DataSource dataSource;
        private ArgumentDefaults argumentDefaults = ArgumentDefaults.none();
        private SignatureSource signatureSource;
        private PlsqlExceptionTranslator exceptionTranslator = new PlsqlExceptionTranslator();
        private CharsetGuard.Policy charsetPolicy = CharsetGuard.Policy.FAIL;
        private String databaseCharset;
        private int indexTableMaxLength = 10_000;
        private boolean retryDiscardedState = true;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        /** Values for arguments the Java methods do not declare (NTENANT, NMODE...). */
        public Builder argumentDefaults(ArgumentDefaults defaults) {
            this.argumentDefaults = Objects.requireNonNull(defaults);
            return this;
        }

        /** Where signatures come from; default: the data dictionary of {@code dataSource}. */
        public Builder signatureSource(SignatureSource source) {
            this.signatureSource = Objects.requireNonNull(source);
            return this;
        }

        public Builder exceptionTranslator(PlsqlExceptionTranslator translator) {
            this.exceptionTranslator = Objects.requireNonNull(translator);
            return this;
        }

        /** What to do with text the database character set cannot store; default FAIL. */
        public Builder charsetPolicy(CharsetGuard.Policy policy) {
            this.charsetPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /** NLS_CHARACTERSET; read from the database when not given and the policy is FAIL. */
        public Builder databaseCharset(String oracleCharset) {
            this.databaseCharset = oracleCharset;
            return this;
        }

        /** Capacity reserved for OUT index-by tables. */
        public Builder indexTableMaxLength(int length) {
            if (length < 1) {
                throw new IllegalArgumentException("indexTableMaxLength must be positive");
            }
            this.indexTableMaxLength = length;
            return this;
        }

        /** Repeat a call once after ORA-04068; default true. */
        public Builder retryDiscardedState(boolean retry) {
            this.retryDiscardedState = retry;
            return this;
        }

        public PlsqlApiFactory build() {
            SignatureSource signatures = signatureSource != null ? signatureSource : new DictionarySignatureSource(dataSource);
            CharsetGuard guard = charsetPolicy == CharsetGuard.Policy.IGNORE
                    ? CharsetGuard.none()
                    : CharsetGuard.forDatabase(databaseCharset != null ? databaseCharset : readCharset(), charsetPolicy);
            return new PlsqlApiFactory(new PlsqlRuntime(dataSource, signatures, new CallPlanner(argumentDefaults),
                    new CallExecutor(indexTableMaxLength, guard), exceptionTranslator, guard, retryDiscardedState));
        }

        private String readCharset() {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = con.prepareStatement(
                    "select value from nls_database_parameters where parameter = 'NLS_CHARACTERSET'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                throw new UncategorizedSQLException("read NLS_CHARACTERSET", null, e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        }
    }
}
