package com.bazaar.identity.profile;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.identity.api.AuditEntry;
import com.bazaar.identity.api.StaffPage;
import com.bazaar.identity.seller.SellerAccess;

@Service
public class AuditQuery {

    private final JdbcTemplate jdbc;

    public AuditQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StaffPage<AuditEntry> list(org.springframework.security.oauth2.jwt.Jwt jwt, int page, int size) {
        SellerAccess access = SellerAccess.from(jwt);
        if (!access.staff()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only staff can read the audit log.");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        long total = jdbc.queryForObject("""
                SELECT (
                    SELECT COUNT(*) FROM seller_audit
                ) + (
                    SELECT COUNT(*) FROM user_audit
                )
                """, Long.class);
        List<AuditEntry> items = jdbc.query("""
                SELECT area, actor_subject, action, target_subject, reason, result_status, created_at
                FROM (
                    SELECT 'SELLER' AS area, actor_subject, action, target_subject, reason, result_status, created_at
                    FROM seller_audit
                    UNION ALL
                    SELECT 'ACCOUNT', actor_subject, action, target_subject, reason, result_status, created_at
                    FROM user_audit
                ) audit_row
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new AuditEntry(
                        rs.getString("area"),
                        rs.getString("actor_subject"),
                        rs.getString("action"),
                        rs.getString("target_subject"),
                        rs.getString("reason"),
                        rs.getString("result_status"),
                        rs.getTimestamp("created_at").toInstant()),
                size, page * (long) size);
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new StaffPage<>(items, page, size, total, pages);
    }
}
