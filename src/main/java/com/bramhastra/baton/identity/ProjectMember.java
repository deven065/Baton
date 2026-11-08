package com.bramhastra.baton.identity;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "project_member")
@IdClass(ProjectMember.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class ProjectMember {

    @Id
    private UUID projectId;

    @Id
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private ProjectRole role;

    private Instant joinedAt = Instant.now();

    public ProjectMember(UUID projectId, UUID userId, ProjectRole role) {
        this.projectId = projectId;
        this.userId = userId;
        this.role = role;
    }

    public record Key(UUID projectId, UUID userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
