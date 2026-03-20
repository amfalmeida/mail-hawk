.PHONY: help install build run test clean docker-build docker-up docker-down docker-clean

help:
	@echo "Mail Hawk - Available commands:"
	@echo ""
	@echo "Development:"
	@echo "  make install        Install Java dependencies"
	@echo "  make build          Build the project"
	@echo "  make run            Run in development mode"
	@echo "  make test           Run tests"
	@echo "  make clean          Clean build artifacts"
	@echo ""
	@echo "Docker:"
	@echo "  make docker-build   Build Docker image"
	@echo "  make docker-up      Run container"
	@echo "  make docker-logs    View container logs"
	@echo "  make docker-down    Stop containers"
	@echo "  make docker-clean   Remove images and volumes"
	@echo ""
	@echo "Home Assistant Add-on:"
	@echo "  make addon-build    Build add-on image"
	@echo "  make addon-run      Build and run add-on"
	@echo "  make addon-logs     View add-on logs"
	@echo ""
	@echo "Setup:"
	@echo "  make setup          Create .env from .env.example"

install:
	@echo "Checking prerequisites..."
	@command -v java >/dev/null 2>&1 || { echo "Error: Java 21+ is required"; exit 1; }
	@echo "Installing dependencies..."
	./mvnw dependency:resolve

build:
	@echo "Building project..."
	./mvnw clean package -DskipTests

run:
	@echo "Running in development mode..."
	./mvnw compile quarkus:dev

test:
	@echo "Running tests..."
	./mvnw test

clean:
	@echo "Cleaning build artifacts..."
	./mvnw clean
	rm -rf target/

docker-build:
	@echo "Building Docker image..."
	docker-compose build

docker-up:
	@echo "Starting container..."
	@if [ ! -f .env ]; then \
		echo "Warning: .env file not found. Using defaults."; \
	fi
	docker-compose up -d

docker-logs:
	docker-compose logs -f

docker-down:
	@echo "Stopping containers..."
	docker-compose down

docker-clean: docker-down
	@echo "Removing Docker image and volumes..."
	docker rmi mail-hawk:latest 2>/dev/null || true
	docker volume rm mail-hawk-data 2>/dev/null || true

addon-build:
	@echo "Building Home Assistant add-on..."
	docker build -t mail-hawk-ha .

addon-run: addon-build
	@echo "Running Home Assistant add-on..."
	docker run -d --name mail-hawk-ha \
		-v /share/mail_hawk:/share/mail_hawk \
		-p 8080:8080 \
		mail-hawk-ha

addon-logs:
	docker logs -f mail-hawk-ha

setup:
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "Created .env file. Please edit it with your settings."; \
	else \
		echo ".env file already exists."; \
	fi