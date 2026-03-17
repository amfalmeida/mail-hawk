package com.amfalmeida.mailhawk.db.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "invoice_configs")
public class InvoiceConfig extends PanacheEntityBase {

    @Id
    public String id;

    @Column(name = "type")
    public String type;

    @Column(name = "from_email")
    public String fromEmail;

    @Column(name = "name")
    public String name;

    @Column(name = "nif")
    public String nif;

    public static InvoiceConfig findByNif(String nif) {
        return find("nif", nif.trim()).firstResult();
    }

    public static InvoiceConfig findByEmail(String email) {
        return find("fromEmail", email.trim()).firstResult();
    }
}
