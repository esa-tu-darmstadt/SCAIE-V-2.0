module scaiev {
	requires org.yaml.snakeyaml;
	requires commons.cli;
	requires org.apache.logging.log4j;
	requires org.apache.logging.log4j.core;

	exports scaiev.scal to org.yaml.snakeyaml;
	opens scaiev.coreconstr to org.yaml.snakeyaml;
	
	//Also require JUnit here, since Eclipse doesn't support several module-infos.
	requires org.junit.jupiter.api;
	requires org.junit.jupiter.params;
}
