package dev.minidoodle.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Avoids an existence query when saving a new entity with an application-assigned ID. */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {
    @Transient
    private boolean newEntity = true;

    @Override
    public abstract ID getId();

    @Override
    @Transient
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        newEntity = false;
    }
}
