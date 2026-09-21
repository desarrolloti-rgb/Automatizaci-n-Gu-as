package com.calimport.guias.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.calimport.guias.model.Rol;

/** Usuarios de prueba para {@code auth.modo=local}. Contraseñas en texto plano: solo para un PC de desarrollo. */
@Configuration
@ConfigurationProperties(prefix = "auth.local")
public class LocalAuthConfig {

    private List<Usuario> usuarios = new ArrayList<>();

    public List<Usuario> getUsuarios() { return usuarios; }
    public void setUsuarios(List<Usuario> usuarios) { this.usuarios = usuarios; }

    public static class Usuario {
        private int employeeId;
        private String nombre;
        private String email;
        private String password;
        /** Si no se declara, REPARTIDOR: así los usuarios que ya estaban no cambian. */
        private Rol rol = Rol.REPARTIDOR;

        public int getEmployeeId() { return employeeId; }
        public void setEmployeeId(int employeeId) { this.employeeId = employeeId; }

        public String getNombre() { return nombre; }
        public void setNombre(String nombre) { this.nombre = nombre; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public Rol getRol() { return rol; }
        public void setRol(Rol rol) { this.rol = rol; }
    }
}
