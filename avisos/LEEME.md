# Avisos de «¿No funciona?»

Aura 1.4.3 o más nueva lee `no-funciona.json` de esta carpeta (rama `main`) y lo muestra en
la pantalla **¿No funciona?** de todas las TV Box, sin necesidad de actualizar Aura.

- Las cajas lo revisan al encender y cada hora mientras están prendidas (GitHub puede tardar
  unos 5 minutos en entregar el cambio).
- Sin internet, cada caja muestra lo último que descargó.
- Lo que no pongas en el archivo se queda con el texto que ya trae Aura.
- Si el archivo queda mal escrito (una coma o comilla de más), las cajas lo ignoran y siguen
  con lo anterior. Puedes revisarlo en https://jsonlint.com antes de guardar.

## Publicar una novedad

Edita `no-funciona.json` en GitHub (ícono del lápiz), cambia los textos y guarda («Commit changes»):

```json
{
  "novedad": {
    "id": "2026-11-28",
    "titulo": "Xuper Crystal Clear ya funciona de noche",
    "texto": "Desde hoy Xuper Crystal Clear ya no se cae a las 10:00 p.m. Si notas algo raro, escríbenos."
  }
}
```

- Sale como una tarjeta ámbar hasta arriba de **¿No funciona?**
- El botón **¿No funciona?** muestra un punto ámbar (●) hasta que la persona abre la pantalla.
- Cambia el `id` (por ejemplo, la fecha) cada vez que publiques una novedad nueva, para que el
  punto vuelva a salir.
- Para quitar la novedad, deja `"texto": ""`.

## Que salga como aviso flotante y en qué horario (Aura 1.4.8 o más nueva)

Agrega a la novedad:

```json
    "flotante": true,
    "horario": "12:00-19:00",
    "desde": "2026-11-28 08:00",
    "hasta": "2026-11-30 19:00"
```

- `flotante`: sale en una ventana al encender (después de la «Guía de uso») y al regresar de una
  app, con los botones «Entendido» y «Ver más» (abre «¿No funciona?»). La casilla «No volver a
  mostrar» viene sin marcar: si el cliente la marca, ya no le flota, pero el mensaje sigue en «¿No funciona?».
- `horario`: horas del día en que existe el mensaje (en «¿No funciona?» y flotante). Fuera de ese
  horario desaparece y vuelve al día siguiente. Conviene terminar a las 19:00, cuando empieza el
  aviso nocturno.
- `desde` / `hasta` (opcionales, `AAAA-MM-DD HH:MM`): fechas entre las que puede flotar.
- Las versiones de Aura anteriores a la 1.4.8 ignoran estos campos: muestran la tarjeta en
  «¿No funciona?» a cualquier hora, sin ventana flotante.

## Cambiar los textos de horarios (opcional)

Puedes agregar cualquiera de estas claves junto a `novedad`:

| Clave | Qué cambia |
|---|---|
| `xuper_pasos` | Lista de pasos de Xuper Crystal Clear / Magis TV, por ejemplo `["Paso 1", "Paso 2"]` |
| `xuper_importante` | El recuadro «Importante: no actualices ni desinstales» |
| `amigonly_texto` | El texto de XuperAmigOnlyTV |
| `amigonly_madrugada` | El recuadro «En la madrugada» |
| `porque` | Los párrafos de «¿Por qué pasa?», como lista |
