package com.open.spring.mvc.certificate;

import java.util.Date;

import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class UserCertificate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(targetEntity = Person.class, optional = false)
    private Person person;

    @ManyToOne(targetEntity = Certificate.class, optional = false)
    private Certificate certificate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateType certificateType;

    @Column(nullable = false)
    private String sprintName;

    @Column(nullable = false)
    private Double averageScore;

    @Column(nullable = false)
    private Date dateIssued;

    public UserCertificate() {
        this.dateIssued = new Date();
    }

    public UserCertificate(Person person, Certificate certificate) {
        this.person = person;
        this.certificate = certificate;
        this.certificateType = CertificateType.COMPLETION; // default
        this.sprintName = "";
        this.averageScore = 0.0;
        this.dateIssued = new Date();
    }

    public UserCertificate(Person person, Certificate certificate, CertificateType certificateType, String sprintName, Double averageScore) {
        this.person = person;
        this.certificate = certificate;
        this.certificateType = certificateType;
        this.sprintName = sprintName;
        this.averageScore = averageScore;
        this.dateIssued = new Date();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
    }

    public Certificate getCertificate() {
        return certificate;
    }

    public void setCertificate(Certificate certificate) {
        this.certificate = certificate;
    }

    public Date getDateIssued() {
        return dateIssued;
    }

    public void setDateIssued(Date dateIssued) {
        this.dateIssued = dateIssued;
    }

    public CertificateType getCertificateType() {
        return certificateType;
    }

    public void setCertificateType(CertificateType certificateType) {
        this.certificateType = certificateType;
    }

    public String getSprintName() {
        return sprintName;
    }

    public void setSprintName(String sprintName) {
        this.sprintName = sprintName;
    }

    public Double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Double averageScore) {
        this.averageScore = averageScore;
    }
}
