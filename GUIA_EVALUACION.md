# Guía de instalación AWS desde cero

> **No necesitas tener EC2, EFS, S3 ni una red creados.** El backend y el pipeline ya están implementados. Sigue estos pasos en orden para ejecutarlos en tu cuenta.

[← README](README.md) · [Postman](docs/POSTMAN.md) · [Video y entrega](docs/VIDEO_Y_ENTREGA.md)

## Índice

1. [Cuentas y repositorios](#1-cuentas-y-repositorios)
2. [Red VPC](#2-red-vpc)
3. [Security groups](#3-security-groups)
4. [Bucket S3](#4-bucket-s3)
5. [Rol de EC2](#5-rol-de-ec2)
6. [Instancia EC2](#6-instancia-ec2)
7. [IP elástica](#7-ip-elástica)
8. [Sistema EFS](#8-sistema-efs)
9. [Preparación y primer montaje](#9-preparación-y-primer-montaje)
10. [Permisos de GitHub Actions](#10-permisos-de-github-actions)
11. [Despliegue automático](#11-despliegue-automático)
12. [Evidencia del doble montaje](#12-evidencia-del-doble-montaje)

---

## Antes de comenzar

Necesitas permisos AWS para crear VPC, EC2, EFS, S3 y roles IAM. En una cuenta de laboratorio, confirma que puedas crear roles o que el docente proporcione roles equivalentes.

Selecciona **N. Virginia (`us-east-1`)** en la esquina superior derecha de AWS. Mantén esta región durante toda la guía.

Sustituye `TU_USUARIO`, `TU_REPOSITORIO` y `REEMPLAZAR_...` por tus datos reales.

Anota los valores a medida que avances:

| Dato | Tu valor |
|---|---|
| Usuario GitHub / repositorio | |
| Usuario Docker Hub | |
| Cuenta AWS — 12 dígitos | |
| ID VPC / subred pública | |
| ID `sg-app` | |
| Nombre del bucket | |
| ID EC2 `i-...` | |
| IP elástica | |
| ID EFS `fs-...` | |
| ARN del rol GitHub | |

## 1. Cuentas y repositorios

### 1.1 Docker Hub

1. Inicia sesión en Docker Hub.
2. Crea dos repositorios **públicos**: `ms-reservas` y `ms-tickets`.
3. En la configuración de cuenta crea un **Personal Access Token** con lectura y escritura.
4. Conserva el token para el paso siguiente.

### 1.2 GitHub

1. Crea un repositorio **público y vacío**, por ejemplo `reservas-eventos-cloud`.
2. No agregues README ni `.gitignore` desde GitHub: el proyecto ya los incluye.
3. Abre **Settings → Secrets and variables → Actions**.
4. En **Secrets**, crea `DOCKERHUB_TOKEN` con el token anterior.
5. En **Variables**, crea `DOCKERHUB_USERNAME` con tu usuario Docker Hub en minúsculas.

### 1.3 Subir el proyecto

Abre una terminal en la **raíz del proyecto**:

```bash
git init -b main
git add .
git commit -m "Implementar reservas, tickets y CI/CD"
git remote add origin https://github.com/TU_USUARIO/TU_REPOSITORIO.git
git push -u origin main
```

Si Git solicita identidad, configura tu propio `user.name` y `user.email` y repite el commit. Autentícate en GitHub cuando se solicite.

**Resultado esperado:** el código aparece en GitHub. En **Actions**, `build` prueba y publica las imágenes; `deploy` se omite mientras no existan las variables AWS. Es normal en este primer paso.

## 2. Red VPC

**Dónde:** AWS → busca **VPC** → **Create VPC**.

| Campo | Seleccionar |
|---|---|
| Resources to create | **VPC and more** |
| Name tag | `reservas` |
| IPv4 CIDR | `10.20.0.0/16` |
| IPv6 | Sin IPv6 |
| Availability Zones | **1** |
| Public subnets | **1** |
| Private subnets | **0** |
| NAT gateways | **None** |
| VPC endpoints | **None** |
| DNS hostnames / resolution | Ambos habilitados |

Pulsa **Create VPC**. El asistente crea también la subred pública, tabla de rutas e Internet Gateway. [Referencia AWS](https://docs.aws.amazon.com/vpc/latest/userguide/create-vpc.html).

**Comprueba:** la tabla de rutas de la subred pública debe tener `0.0.0.0/0 → igw-...`.

**Anota:** ID VPC, ID subred y zona de disponibilidad, por ejemplo `us-east-1a`.

## 3. Security groups

**Dónde:** EC2 → **Security Groups → Create security group**. Crea ambos en la VPC anterior.

### 3.1 Grupo `sg-app`

Descripción: `API reservas y tickets`.

Reglas de **entrada**:

| Tipo | Puerto | Origen |
|---|---|---|
| Custom TCP | `8080` | **My IP** |
| Custom TCP | `8081` | **My IP** |

Si tu compañero usa Postman desde otra conexión, agrega su IP pública con `/32` para ambos puertos. Conserva la regla de salida predeterminada que permite tráfico saliente.

### 3.2 Grupo `sg-efs`

Descripción: `EFS desde EC2 reservas`.

| Tipo | Puerto | Origen |
|---|---|---|
| NFS | `2049` | **Selecciona el ID de `sg-app`** |

El origen de EFS es el grupo de EC2, no tu IP doméstica. [Reglas EFS](https://docs.aws.amazon.com/efs/latest/ug/network-access.html).

**Resultado:** dos grupos en la misma VPC. No necesitas abrir SSH; usarás Session Manager.

## 4. Bucket S3

**Dónde:** S3 → **Create bucket**.

| Campo | Seleccionar |
|---|---|
| Tipo | General purpose |
| Región | `us-east-1` |
| Nombre | Nombre único, por ejemplo `tickets-eventos-tuidentificador` |
| Object ownership | ACLs disabled |
| Block all public access | Activado |
| Versioning | Desactivado |
| Default encryption | SSE-S3 |

Pulsa **Create bucket** y anota su nombre exacto.

**No crees carpetas ni subas PDF manualmente.** La aplicación generará esta estructura:

```text
evento<UUID>/
└── usuarioX.../
    └── ticket<UUID>.pdf
```

## 5. Rol de EC2

Permite acceder a S3 y recibir comandos de Systems Manager.

### 5.1 Política S3

1. IAM → **Policies → Create policy → JSON**.
2. Copia [deploy/iam/ec2-s3.json](deploy/iam/ec2-s3.json).
3. Sustituye ambas apariciones de `REEMPLAZAR_BUCKET` por el nombre real.
4. Guarda como **`reservas-s3-policy`**.

### 5.2 Rol

1. IAM → **Roles → Create role**.
2. Trusted entity: **AWS service**; use case: **EC2**.
3. Adjunta **`AmazonSSMManagedInstanceCore`** y **`reservas-s3-policy`**.
4. Guarda como **`ec2-reservas-role`**.

La política administrada permite utilizar Session Manager con la instancia. [Referencia AWS](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-getting-started-instance-profile.html).

## 6. Instancia EC2

**Dónde:** EC2 → **Instances → Launch instances**.

| Campo | Seleccionar |
|---|---|
| Name | `ec2-reservas` |
| AMI | **Amazon Linux 2023** |
| Arquitectura | **64-bit x86** |
| Instance type | **t3.medium** — 4 GiB RAM |
| Key pair | Proceed without a key pair; usarás Session Manager |
| VPC | La VPC `reservas` |
| Subnet | La subred pública del paso 2 |
| Auto-assign public IP | Disabled; asignarás IP elástica |
| Firewall | Existing security group → **sg-app** |
| Storage | **20 GiB gp3**, cifrado activado |

En **Advanced details**:

| Campo | Valor |
|---|---|
| IAM instance profile | `ec2-reservas-role` |
| Metadata accessible | Enabled |
| Metadata version | **V2 only / IMDSv2 required** |
| Metadata response hop limit | **2** |

El hop limit permite que Docker acceda a las credenciales del rol. [Opciones IMDS](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-options.html).

Pulsa **Launch instance**. Espera estado **Running** y anota su ID `i-...` y zona.

## 7. IP elástica

1. EC2 → **Elastic IPs → Allocate Elastic IP address → Allocate**.
2. Selecciona la IP → **Actions → Associate Elastic IP address**.
3. Resource type: **Instance**.
4. Elige `ec2-reservas` y su IP privada.
5. Pulsa **Associate**.

**Resultado:** EC2 muestra esa dirección en **Elastic IP addresses**. Será la IP utilizada en Postman.

Las IPv4 públicas generan cargos, incluidas las IP elásticas sin asociar. [Referencia AWS](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/elastic-ip-addresses-eip.html).

## 8. Sistema EFS

**Dónde:** EFS → **Create file system → Customize**.

1. Nombre: **`efs-tickets`**.
2. Tipo: **Regional**, con cifrado en reposo.
3. Performance mode: **General Purpose**. Throughput: **Bursting** para este laboratorio.
4. Desactiva las transiciones automáticas entre clases de almacenamiento para simplificar la demostración.
5. En **Network access**, selecciona la VPC `reservas`.
6. Crea un **mount target** en la subred de la misma zona que EC2.
7. Asocia **`sg-efs`** al mount target; quita el grupo predeterminado si aparece.
8. Deja sin política personalizada de sistema de archivos: usarás NFS con TLS.
9. Crea el sistema y espera mount target **Available**.

**Anota:** ID `fs-...`. No necesitas access point para esta implementación.

**Comprueba:** EC2 y el mount target están en la misma VPC y zona.

## 9. Preparación y primer montaje

### 9.1 Abrir la terminal

EC2 → selecciona la instancia → **Connect → Session Manager → Connect**.

Si no está disponible, espera unos minutos y revisa rol, IP elástica, ruta al Internet Gateway y salida HTTPS del security group.

### 9.2 Preparar EC2

En esa terminal descarga el script de **tu repositorio**:

```bash
curl -fsSL https://raw.githubusercontent.com/TU_USUARIO/TU_REPOSITORIO/main/deploy/bootstrap-ec2.sh -o /tmp/bootstrap-ec2.sh
```

Ejecuta reemplazando ID y bucket:

```bash
sudo bash /tmp/bootstrap-ec2.sh fs-REEMPLAZAR us-east-1 REEMPLAZAR_BUCKET
```

El script:

- Instala Docker y el cliente EFS.
- Monta EFS con TLS en `/mnt/efs` y configura `/etc/fstab`.
- Crea `/mnt/efs/tickets` con permisos para el contenedor.
- Hace que Docker espere el montaje al iniciar.
- Genera la contraseña PostgreSQL y guarda configuración en `/opt/reservas/app.env`, legible solo por root.

El montaje utiliza `mount -t efs -o tls fs-ID:/ /mnt/efs`. [Referencia AWS](https://docs.aws.amazon.com/efs/latest/ug/mounting-fs-mount-helper-ec2-linux.html).

### 9.3 Comprobar

```bash
df -h
df -hT /mnt/efs
findmnt -T /mnt/efs
sudo ls -ld /mnt/efs/tickets
```

**Resultado:** `/mnt/efs` está sobre `nfs4`. Con TLS el origen puede aparecer como `127.0.0.1:/` por el proxy del cliente. [Helper EFS](https://docs.aws.amazon.com/efs/latest/ug/efs-mount-helper.html).

Los microservicios todavía no están iniciados: **GitHub Actions los levantará en el paso 11**.

## 10. Permisos de GitHub Actions

GitHub obtiene credenciales temporales mediante **OIDC** y ejecuta el despliegue mediante **SSM Run Command**.

### 10.1 Proveedor OIDC

IAM → **Identity providers → Add provider**:

| Campo | Valor |
|---|---|
| Provider type | OpenID Connect |
| Provider URL | `https://token.actions.githubusercontent.com` |
| Audience | `sts.amazonaws.com` |

Si ya existe, reutilízalo.

### 10.2 Política de despliegue

1. IAM → **Policies → Create policy → JSON**.
2. Copia [deploy/iam/github-deploy.json](deploy/iam/github-deploy.json).
3. Reemplaza región, cuenta AWS e ID de instancia.
4. Guarda como **`github-reservas-deploy-policy`**.

### 10.3 Rol de despliegue

1. IAM → **Roles → Create role → Custom trust policy**.
2. Copia [deploy/iam/github-trust.json](deploy/iam/github-trust.json).
3. Reemplaza `REEMPLAZAR_CUENTA` por tu cuenta de 12 dígitos.
4. Para `REEMPLAZAR_SUBJECT_EXACTO`, comienza con:

```text
repo:TU_USUARIO/TU_REPOSITORIO:ref:refs/heads/main
```

5. Adjunta `github-reservas-deploy-policy`.
6. Guarda como **`github-deploy-reservas`** y copia su **ARN**.

> GitHub puede emitir un subject con IDs: `repo:USUARIO@ID/REPO@ID:ref:refs/heads/main`. El workflow imprime **solo el subject** antes de autenticarse. Si falla la autenticación inicial, copia ese valor exacto a la política de confianza y vuelve a ejecutar. No uses comodines. [Formatos OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).

### 10.4 Variables en GitHub

Repositorio → **Settings → Secrets and variables → Actions → Variables**:

| Nombre | Valor |
|---|---|
| `AWS_REGION` | `us-east-1` |
| `EC2_INSTANCE_ID` | ID `i-...` |
| `AWS_DEPLOY_ROLE_ARN` | ARN de `github-deploy-reservas` |
| `DOCKERHUB_USERNAME` | Ya configurada |

En **Secrets** debe seguir existiendo `DOCKERHUB_TOKEN`. No necesitas guardar claves AWS ni la contraseña PostgreSQL en GitHub.

## 11. Despliegue automático

Desde la raíz del proyecto en tu computador:

```bash
git commit --allow-empty -m "Desplegar con AWS configurado"
git push origin main
```

Ve a **GitHub → Actions → Tickets CI-CD**. Debe completar:

```text
Push main → pruebas → imágenes Docker → Docker Hub → EC2 → salud y montaje
```

**Resultado:** los dos `build` y `deploy` quedan verdes. Si `deploy` se omite, faltan las variables del rol o de la instancia.

En EC2:

```bash
sudo docker ps
curl -f http://localhost:8081/actuator/health
curl -f http://localhost:8080/actuator/health
```

Deben aparecer `reservas-db`, `ms-reservas` y `ms-tickets`; ambas consultas devuelven `UP`.

**Ahora usa Postman:** sigue [POSTMAN.md](docs/POSTMAN.md), seleccionando el entorno AWS y tu IP elástica.

## 12. Evidencia del doble montaje

### 12.1 Linux de EC2

```bash
df -h
df -hT /mnt/efs
sudo docker inspect ms-tickets --format '{{json .Mounts}}'
```

`Mounts` debe mostrar:

```text
Source:      /mnt/efs/tickets
Destination: /app/efs
Type:        bind
```

### 12.2 Linux del contenedor

```bash
sudo docker exec -it ms-tickets sh
```

Ya dentro:

```bash
df -h
df -h /app/efs
grep ' /app/efs ' /proc/mounts
find /app/efs -type f
exit
```

**Explicación:** “Monté EFS en EC2 y compartí su carpeta `tickets` con el contenedor. Los PDF escritos en `/app/efs` están realmente en EFS”.

### 12.3 Después de generar un ticket

Copia `s3Key` de Postman y sustituye `CLAVE_REAL`:

```bash
sudo sha256sum /mnt/efs/tickets/CLAVE_REAL
sudo docker exec ms-tickets sha256sum /app/efs/CLAVE_REAL
```

Ambos hashes deben coincidir con `sha256` de la respuesta. Abre S3, actualiza el bucket y muestra el mismo PDF.

---

## Si algo falla

| Problema | Revisión |
|---|---|
| Session Manager no conecta | Rol, IP elástica, ruta al Internet Gateway, salida HTTPS y agente SSM |
| EFS no monta | VPC/zona, mount target Available, 2049 desde `sg-app` |
| No puede escribir PDF | Propietario `10001:10001` de `/mnt/efs/tickets` |
| No hay credenciales AWS | Rol EC2, IMDS habilitado, hop limit 2 |
| S3 deniega acceso | Nombre del bucket, política y región |
| GitHub no asume rol | Subject exacto en Trust relationships y audiencia |
| SSM falla | Instancia correcta, permisos SendCommand y nodo SSM Online |
| Postman no conecta | IP elástica, 8080/8081 y tu IP pública actual en `sg-app` |
| Contenedor se detiene | Revisa los logs siguientes |

```bash
sudo docker logs --tail 100 ms-reservas
sudo docker logs --tail 100 ms-tickets
sudo docker logs --tail 100 reservas-db
```

No cambies la contraseña del archivo `app.env` sin actualizar también PostgreSQL. El script conserva el archivo si ya existe.

## Antes de grabar

- [ ] Postman usa la IP elástica.
- [ ] Actions muestra build y deploy correctos.
- [ ] Docker Hub contiene las imágenes del commit.
- [ ] `df -h` evidencia EFS en EC2 y contenedor.
- [ ] Generar/modificar/eliminar se refleja en S3.
- [ ] PDF/ZIP abren correctamente y SHA-256 coincide.
- [ ] Historial y estadísticas muestran valores correctos.

Siguiente: **[Video y entregables](docs/VIDEO_Y_ENTREGA.md)**.
