# Cava Realtime Client

Home of the Interactive Oceans Realtime Data Service Client

## Run with docker-compose

1. Build the images

```
docker-compose -f resources/docker/docker-compose.yaml build
```

2. Set OOI Username and Token

```
export OOI_USERNAME=MyUser
export OOI_TOKEN=AS3cret
```

3. Bring up all the components

```
docker-compose -f resources/docker/docker-compose.yaml up
```

4. Go to http://localhost:8081/feed?ref=RS03AXPS-SF03A-2A-CTDPFA302

5. Stop the components and tear down (Ctrl+C in the terminal)

```
docker-compose -f resources/docker/docker-compose.yaml down
```