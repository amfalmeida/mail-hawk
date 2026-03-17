package com.amfalmeida.common;

import java.util.HashSet;
import java.util.Set;
import org.slf4j.MDC;
import lombok.Getter;

public class MdcSetter implements AutoCloseable {

    private final Set<MdcFields> addedMdcValues = new HashSet<>();

    @Getter
    public enum MdcFields {
        ID("id");

        private final String id;

        MdcFields(final String id) {
            this.id = id;
        }
    }

    public MdcSetter(final String id) {
        add(MdcFields.ID, id);
    }

    public void add(final MdcFields mdcFields, final String value) {
        MDC.put(mdcFields.getId(), value);
        addedMdcValues.add(mdcFields);
    }

    @Override
    public void close() {
        addedMdcValues.forEach(mdcFields -> MDC.remove(mdcFields.getId()));
    }
    
}
