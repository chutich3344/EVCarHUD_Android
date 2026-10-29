"""Xuất model YOLOX (Apache-2.0) ra ONNX đúng định dạng app EVCarHUD đang đọc.

App (Detector.kt) gửi ảnh RGB 0..1, letterbox nền xám, và đọc đầu ra [1, 4+nc, N]
(cx, cy, w, h theo pixel đầu vào, rồi điểm từng lớp) giống YOLOv8/11. YOLOX thì cần ảnh BGR 0..255
và ra [1, N, 5+nc] có điểm "objectness" riêng.
Script này gói cả hai đầu vào trong đồ thị ONNX nên app không phải sửa gì khi đổi model.

Cách dùng (cần mã nguồn YOLOX: git clone https://github.com/Megvii-BaseDetection/YOLOX):
  PYTHONPATH=YOLOX python yolox_export.py -n yolox-s -c yolox_s.pth -o yolox_s.onnx
  PYTHONPATH=YOLOX python yolox_export.py -f exps/vn_road.py -c best_ckpt.pth -o yolox_tiny.onnx
(yolox_s.pth: trọng số COCO ở Release 0.1.1rc0 của YOLOX, đã thử dùng không cần --legacy.)
--legacy chỉ dành cho trọng số rất cũ cần ảnh RGB chuẩn hoá mean/std.
"""
import argparse

import torch
from torch import nn
from yolox.exp import get_exp


class AppHead(nn.Module):
    def __init__(self, model, legacy):
        super().__init__()
        self.model = model
        self.legacy = legacy
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

    def forward(self, x):                      # x: RGB 0..1 [1, 3, H, W]
        if self.legacy:
            x = (x - self.mean) / self.std
        else:
            x = x.flip(1) * 255.0              # RGB -> BGR, 0..255
        y = self.model(x)                      # [1, N, 5+nc]: cx cy w h obj cls...
        box = y[..., :4]
        score = y[..., 5:] * y[..., 4:5]       # điểm lớp = obj * cls, như YOLOv8
        return torch.cat([box, score], -1).permute(0, 2, 1)   # [1, 4+nc, N]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", "--name", default=None, help="yolox-nano / yolox-tiny / yolox-s / yolox-m ...")
    ap.add_argument("-f", "--exp_file", default=None, help="file exp khi tự huấn luyện")
    ap.add_argument("-c", "--ckpt", required=True)
    ap.add_argument("-o", "--output", default="yolox_tiny.onnx")
    ap.add_argument("--height", type=int, default=384)
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--legacy", action="store_true")
    a = ap.parse_args()

    exp = get_exp(a.exp_file, a.name)
    model = exp.get_model()
    ckpt = torch.load(a.ckpt, map_location="cpu")
    model.load_state_dict(ckpt.get("model", ckpt))
    model.eval()
    model.head.decode_in_inference = True
    net = AppHead(model, a.legacy).eval()

    dummy = torch.zeros(1, 3, a.height, a.width)
    torch.onnx.export(net, dummy, a.output, input_names=["images"], output_names=["output0"],
                      opset_version=12, do_constant_folding=True, dynamo=False)
    try:
        import onnx
        from onnxsim import simplify
        m, ok = simplify(onnx.load(a.output))
        if ok:
            onnx.save(m, a.output)
    except ImportError:
        pass
    print("Đã xuất", a.output)


if __name__ == "__main__":
    main()
