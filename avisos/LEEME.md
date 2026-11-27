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

## Cambiar los textos de horarios (opcional)

Puedes agregar cualquiera de estas claves junto a `novedad`:

| Clave | Qué cambia |
|---|---|
| `xuper_pasos` | Lista de pasos de Xuper Crystal Clear / Magis TV, por ejemplo `["Paso 1", "Paso 2"]` |
| `xuper_importante` | El recuadro «Importante: no actualices ni desinstales» |
| `amigonly_texto` | El texto de XuperAmigOnlyTV |
| `amigonly_madrugada` | El recuadro «En la madrugada» |
| `porque` | Los párrafos de «¿Por qué pasa?», como lista |
