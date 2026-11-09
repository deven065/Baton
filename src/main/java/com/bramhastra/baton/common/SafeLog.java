package com.bramhastra.baton.common;

import java.sql.SQLException;

/**
 * Describes an error for the server log without its message. Exception messages can carry company content
 * (a duplicate key value, a line of a document, a model response), and Baton's logs must never hold that.
 * What is kept: the exception types, SQL state codes, and where in Baton's code it happened.
 */
public final class SafeLog {

    private SafeLog() {
    }

    public static String describe(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
            if (depth > 0) {
                out.append(" <- ");
            }
            out.append(t.getClass().getSimpleName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                out.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
            if (t.getCause() == t) {
                break;
            }
        }
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("com.bramhastra.")) {
                String cls = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1);
                out.append(" at ").append(cls).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                break;
            }
        }
        return out.toString();
    }
}
