#!/usr/bin/env bash
kind delete cluster --name recovery-demo; kind delete cluster --name recovery-target
docker rm -f minio dashboard-mysql
