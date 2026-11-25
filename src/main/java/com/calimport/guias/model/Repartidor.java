package com.calimport.guias.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;

@Entity
public class Repartidor {

    /** EmployeeID de SAP. Es la identidad: no se genera un id propio (ver javadoc de la clase). */
    @Id
    private int employeeId;

    @NotBlank
    @Column(nullable = false)
    private String nombre;

    /** Con este correo se autentica, igual que en el portal de proveedores. */
    @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    /** Refleja el Active de SAP. Un repartidor inactivo no recibe asignaciones. */
    @Column(nullable = false)
    private boolean activo;

    /** Se refresca en cada login. Solo los Despachador reciben guías. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rol rol = Rol.Despachador;

    /**
     * Si suele volver a bodega al terminar la ruta (uno vuelve y otro no). Es lo que trae
     * marcado el formulario de la ruta; no viene de SAP, así que el login no lo toca.
     * El nombre va explícito: la convención de Spring no separa "AB" y buscaría "vuelveabodega".
     */
    @Column(name = "vuelve_a_bodega", nullable = false)
    private boolean vuelveABodega;

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

    public Rol getRol() {
        return rol;
    }

    public void setRol(Rol rol) {
        this.rol = rol;
    }

    public boolean isVuelveABodega() {
        return vuelveABodega;
    }

    public void setVuelveABodega(boolean vuelveABodega) {
        this.vuelveABodega = vuelveABodega;
    }
}
