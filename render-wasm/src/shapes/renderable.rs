use skia_safe as skia;
use uuid::Uuid;
use edit_xml::{Document};

use super::{draw_image_in_container, Fill, Kind, Shape};
use crate::math::Rect;
use crate::render::{ImageStore, Renderable};

impl Renderable for Shape {
    fn render(
        &self,
        surface: &mut skia_safe::Surface,
        images: &ImageStore,
        font_provider: &skia::textlayout::TypefaceFontProvider,
    ) -> Result<(), String> {
        println!("self.selrect {:?}", self.selrect);
        let svg_canvas = skia::svg::Canvas::new(
            // skia::Rect::from_size((self.selrect.right - self.selrect.left, self.selrect.bottom - self.selrect.top)),
            skia::Rect::from_size((100000, 100000)),
            None,
        );

        let transform = self.transform.to_skia_matrix();

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let center = self.selrect.center();
        let mut matrix = skia::Matrix::new_identity();
        matrix.pre_translate(center);
        matrix.pre_concat(&transform);
        matrix.pre_translate(-center);

        surface.canvas().concat(&matrix);

        if let Kind::SVGRaw(sr) = &self.kind {
            let dom = skia::svg::Dom::from_str(
                sr.content.to_string(),
                skia::FontMgr::from(font_provider.clone()),
            )
            .unwrap();
            dom.render(surface.canvas());
        }

        for fill in self.fills().rev() {
            render_fill(surface, &svg_canvas, images, fill, self.selrect, &self.kind);
        }

        let svg_data = svg_canvas.end();
        let svg = String::from_utf8_lossy(svg_data.as_bytes());

        if let Kind::Path(_) = &self.kind {
          let mut doc = Document::parse_str(&svg).unwrap();
          let root = doc.root_element().unwrap();
          let path = root.find(&doc, "path").unwrap();
          path.set_attribute(&mut doc, "fill-rule", "evenodd");
          let svg_mod = doc.write_str().unwrap();

          let dom = skia::svg::Dom::from_str(
            svg_mod,
              skia::FontMgr::from(font_provider.clone()),
          )
          .unwrap();
          dom.render(surface.canvas());
          // println!("svg: {:?}", svg_mod);
        }

        //Is this needed here?
        // let mut paint = skia::Paint::default();
        // paint.set_blend_mode(self.blend_mode.into());
        // paint.set_alpha_f(self.opacity);

        Ok(())
    }

    fn blend_mode(&self) -> crate::render::BlendMode {
        self.blend_mode
    }

    fn opacity(&self) -> f32 {
        self.opacity
    }

    fn hidden(&self) -> bool {
        self.hidden
    }

    fn bounds(&self) -> Rect {
        self.selrect
    }

    fn clip(&self) -> bool {
        self.clip_content
    }

    fn children_ids(&self) -> Vec<Uuid> {
        self.children.clone()
    }
}

fn render_fill(
    surface: &mut skia::Surface,
    svg_canvas: &skia::svg::Canvas,
    images: &ImageStore,
    fill: &Fill,
    selrect: Rect,
    kind: &Kind,
) {
    match (fill, kind) {
        (Fill::Image(image_fill), kind) => {
            let image = images.get(&image_fill.id());
            if let Some(image) = image {
                draw_image_in_container(
                    surface.canvas(),
                    &image,
                    image_fill.size(),
                    kind,
                    &fill.to_paint(&selrect),
                );
                draw_image_in_container(
                    svg_canvas,
                    &image,
                    image_fill.size(),
                    kind,
                    &fill.to_paint(&selrect),
                );
            }
        }
        (_, Kind::Rect(rect)) => {
            surface.canvas().draw_rect(rect, &fill.to_paint(&selrect));
            println!("DRAW RECT2");
            svg_canvas.draw_rect(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Circle(rect)) => {
            surface.canvas().draw_oval(rect, &fill.to_paint(&selrect));
            println!("DRAW Circle2");
            svg_canvas.draw_oval(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Path(path)) => {
            // surface
            //     .canvas()
            //     .draw_path(&path.to_skia_path(), &fill.to_paint(&selrect));
            // println!("DRAW PATH2, {:?}", path);
            svg_canvas
                .draw_path(&path.to_skia_path(), &fill.to_paint(&selrect));
        }
        (_, Kind::SVGRaw(_sr)) => {
            // NOOP
        }
    }
}
