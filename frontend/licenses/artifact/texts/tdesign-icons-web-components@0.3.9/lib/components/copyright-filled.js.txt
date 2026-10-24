'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var omi_module = require('../_chunks/dep-36eb48b6.js');
var icon = require('../icon.js');
require('../util/classname.js');
require('../util/size-props.js');
require('../util/check-url-and-load.js');

var _class;
function ownKeys(e, r) { var t = Object.keys(e); if (Object.getOwnPropertySymbols) { var o = Object.getOwnPropertySymbols(e); r && (o = o.filter(function (r) { return Object.getOwnPropertyDescriptor(e, r).enumerable; })), t.push.apply(t, o); } return t; }
function _objectSpread(e) { for (var r = 1; r < arguments.length; r++) { var t = null != arguments[r] ? arguments[r] : {}; r % 2 ? ownKeys(Object(t), !0).forEach(function (r) { omi_module._defineProperty(e, r, t[r]); }) : Object.getOwnPropertyDescriptors ? Object.defineProperties(e, Object.getOwnPropertyDescriptors(t)) : ownKeys(Object(t)).forEach(function (r) { Object.defineProperty(e, r, Object.getOwnPropertyDescriptor(t, r)); }); } return e; }
function _createSuper(t) { var r = _isNativeReflectConstruct(); return function () { var e, o = omi_module._getPrototypeOf(t); if (r) { var s = omi_module._getPrototypeOf(this).constructor; e = Reflect.construct(o, arguments, s); } else e = o.apply(this, arguments); return omi_module._possibleConstructorReturn(this, e); }; }
function _isNativeReflectConstruct() { try { var t = !Boolean.prototype.valueOf.call(Reflect.construct(Boolean, [], function () {})); } catch (t) {} return (_isNativeReflectConstruct = function _isNativeReflectConstruct() { return !!t; })(); }
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __decorateClass = function __decorateClass(decorators, target, key, kind) {
  var result = kind > 1 ? void 0 : kind ? __getOwnPropDesc(target, key) : target;
  for (var i = decorators.length - 1, decorator; i >= 0; i--) if (decorator = decorators[i]) result = (kind ? decorator(target, key, result) : decorator(result)) || result;
  if (kind && result) __defProp(target, key, result);
  return result;
};
var element = {
  "attributes": {
    "fill": "none",
    "viewBox": "0 0 24 24",
    "width": "1em",
    "height": "1em"
  },
  "nodeName": "svg",
  "children": [{
    "attributes": {
      "fill": "currentColor",
      "d": "M12 1C18.0751 1 23 5.92487 23 12C23 18.0751 18.0751 23 12 23C5.92487 23 1 18.0751 1 12C1 5.92487 5.92487 1 12 1ZM9.52513 14.4756C8.15829 13.1088 8.15829 10.8927 9.52513 9.52586C10.892 8.15902 13.108 8.15902 14.4749 9.52586L15.182 10.233L16.5962 8.81875L15.8891 8.11165C13.7412 5.96376 10.2588 5.96376 8.11091 8.11165C5.96303 10.2595 5.96303 13.7419 8.11091 15.8898C10.2588 18.0377 13.7412 18.0377 15.8891 15.8898L16.5962 15.1827L15.182 13.7685L14.4749 14.4756C13.108 15.8424 10.892 15.8424 9.52513 14.4756Z"
    },
    "nodeName": "path",
    "children": []
  }]
};
exports['default'] = (_class = /*#__PURE__*/function (_IconBase) {
  omi_module._inherits(CopyrightFilledIcon, _IconBase);
  var _super = _createSuper(CopyrightFilledIcon);
  function CopyrightFilledIcon() {
    var _this;
    omi_module._classCallCheck(this, CopyrightFilledIcon);
    _this = _super.call(this);
    _this.props = _objectSpread(_objectSpread({}, _this.props), {}, {
      id: "copyright-filled"
    });
    return _this;
  }
  return omi_module._createClass(CopyrightFilledIcon);
}(icon.IconBase), omi_module._defineProperty(_class, "icon", element), _class);
exports['default'] = __decorateClass([omi_module.Q("t-icon-copyright-filled")], exports['default']);

exports.default = exports['default'];
//# sourceMappingURL=copyright-filled.js.map
