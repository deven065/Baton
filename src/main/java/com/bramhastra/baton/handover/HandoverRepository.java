package com.bramhastra.baton.handover;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.source.SourceRepository;

@Repository
public class HandoverRepository {

    private static final String SELECT = """
            SELECT h.*, u.display_name AS departing_name FROM handover h
            JOIN app_user u ON u.id = h.departing_user_id
            """;

    private final JdbcClient jdbc;

    public HandoverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID projectId, UUID departingUserId, String title, String scenario, List<String> aliases,
                       UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO handover (project_id, departing_user_id, title, scenario, departing_aliases, created_by)
                VALUES (:projectId, :departing, :title, :scenario, CAST(:aliases AS text[]), :createdBy)
                RETURNING id
                """)
                .param("projectId", projectId)
                .param("departing", departingUserId)
                .param("title", title)
                .param("scenario", scenario)
                .param("aliases", AccessPolicy.toPgArray(aliases))
                .param("createdBy", createdBy)
                .query(UUID.class).single();
    }

    public Optional<Handover> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE h.id = :id").param("id", id).query(this::map).optional();
    }

    public List<Handover> listForProject(UUID projectId) {
        return jdbc.sql(SELECT + " WHERE h.project_id = :p ORDER BY h.created_at DESC")
                .param("p", projectId).query(this::map).list();
    }

    /** Only the title changes; versions already approved keep the title they were frozen with. */
    public void rename(UUID id, String title) {
        jdbc.sql("UPDATE handover SET title = :t, updated_at = now() WHERE id = :id").param("id", id).param("t", title).update();
    }

    /** Status change guarded by the expected current status, so two requests cannot both win. */
    public void transition(UUID id, HandoverStatus from, HandoverStatus to) {
        int n = jdbc.sql("""
                UPDATE handover SET status = :to, updated_at = now(), version = version + 1
                WHERE id = :id AND status = :from
                """)
                .param("id", id).param("from", from.name()).param("to", to.name()).update();
        if (n != 1) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Handover changed state; reload and retry");
        }
    }

    private Handover map(ResultSet rs, int n) throws SQLException {
        Integer versionNo = (Integer) rs.getObject("current_version_no");
        return new Handover(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("departing_user_id", UUID.class), rs.getString("departing_name"), rs.getString("title"),
                rs.getString("scenario"), HandoverStatus.valueOf(rs.getString("status")), versionNo,
                SourceRepository.textArray(rs.getArray("departing_aliases")),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getInt("version"));
    }
}
