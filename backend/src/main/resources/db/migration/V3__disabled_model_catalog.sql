-- Reviewed metadata only. No provider endpoint, secret, media call or model approval is seeded.
INSERT INTO approved_models(provider_id,model_id,label,enabled,capabilities,options_json) VALUES
('qwenImage21Local','qwenImage21Text','Qwen Image 2.1 Text',FALSE,'["image"]','{"resolutions":["1K","0.5MP","2K"],"defaultResolution":"1K"}'),
('qwenImage21Local','qwenImage21Edit','Qwen Image 2.1 Edit',FALSE,'["image"]','{"resolutions":["1K","0.5MP","2K"],"defaultResolution":"1K"}'),
('h3Local3060','h3T2v','H3 Text to Video',FALSE,'["video"]','{}'),
('h3Local3060','h3I2v','H3 First Frame',FALSE,'["video"]','{}'),
('h3Local3060','h3Fl2v','H3 First and Last Frame',FALSE,'["video"]','{}'),
('h3Local3060','h3L2v','H3 Last Frame',FALSE,'["video"]','{}'),
('h3Local3060','h3R2v','H3 References',FALSE,'["video"]','{}'),
('deepseek','deepseek-chat','DeepSeek Chat',FALSE,'["text","agent"]','{}');
