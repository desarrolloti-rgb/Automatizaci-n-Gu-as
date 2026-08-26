package com.calimport.guias.model;


import jakarta.validation.constraints.NotBlank;

public class Repartidor {

    /** EmployeeID de SAP. Es la identidad. */
    private int employeeId;

    @NotBlank
    private String nombre;

    /** Con este correo se autentica, igual que en el portal de proveedores. */
    @NotBlank
    private String email;

    /** Refleja el Active de SAP. Un repartidor inactivo no recibe asignaciones. */
    private boolean activo;

    // --- getters y setters ---

    public int getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(int employeeId) {
        this.employeeId = employeeId;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
