all: menuconfig

gen_config: clean_config
	python3 gen_config.py

menuconfig: gen_config
	python3 -m menuconfig

build:
	python3 dispatch.py

gen_ci_config:
	python3 gen_ci_config.py

ci: gen_ci_config build

clean_config:
	rm -rf build/ISAX

clean:
	rm -rf build

mrproper: clean
	rm -rf outputs
	rm -rf test_results

.PHONY : build menuconfig gen_config clean clean_config mrproper ci gen_ci_config
