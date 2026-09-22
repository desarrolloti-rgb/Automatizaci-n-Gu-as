package com.calimport.guias;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} es por el reintento de sincronización con SAP: las guías que
 * se resolvieron en la calle sin que el Service Layer contestara se vuelven a enviar solas
 * (ver {@link com.calimport.guias.service.SincronizacionSapService}).
 */
@SpringBootApplication
@EnableScheduling
public class GuiasBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(GuiasBackendApplication.class, args);
	}

}
