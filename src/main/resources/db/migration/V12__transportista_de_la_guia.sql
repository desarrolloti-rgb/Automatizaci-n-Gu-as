-- El transportista externo que lleva la guia (SAMEX), o NULL si la reparte Calimport. Lo elige
-- bodega en la app, en el mismo selector que el repartidor; la sincronizacion con SAP no lo toca.
-- Una guia con transportista no tiene repartidor ni entra en rutas. En SAP se ve en U_Despachador.
ALTER TABLE guia ADD COLUMN transportista VARCHAR(50);
