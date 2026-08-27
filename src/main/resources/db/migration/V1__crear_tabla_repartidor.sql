-- Copia local de los repartidores. La identidad viene de SAP (EmployeesInfo.EmployeeID),
-- por eso employee_id es la clave primaria y no se autogenera.
CREATE TABLE repartidor (
    employee_id INTEGER      NOT NULL,
    nombre      VARCHAR(255) NOT NULL,
    -- Es la credencial de login: repetirlo haria ambiguo quien entra.
    email       VARCHAR(255) NOT NULL,
    -- Refleja el Active de SAP. Un repartidor inactivo no recibe asignaciones.
    activo      BOOLEAN      NOT NULL,

    CONSTRAINT pk_repartidor        PRIMARY KEY (employee_id),
    CONSTRAINT uq_repartidor_email  UNIQUE (email)
);
