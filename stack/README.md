create self-signed certificates:
`openssl req  -nodes -new -x509  -keyout caddy-key.pem -out caddy-cert.pem`
create docker stack:
`docker stack deployc -c file stack_name`
create docker network:
`docker network create --driver overlay 3d-network`
examples of docker swarm commands:
`docker service ls`
`docker service logs -f service_name`
`docker service ps service_name`