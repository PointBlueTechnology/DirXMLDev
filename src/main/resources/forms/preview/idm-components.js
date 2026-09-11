/*
 * Placeholders for the NetIQ/OpenText custom component types used by IDM JSON forms, so the
 * open-source Form.io renderer can lay a form out. They are stubs: they show the label, the
 * component type and a plain control where the vendor renderer would query the Identity Vault.
 * They are NOT the vendor renderer — see docs/forms.md, Option C.
 */
(function () {
  if (typeof Formio === 'undefined') { return; }
  var Components = Formio.Components;
  var TextFieldComponent = Components.components.textfield;
  var HTMLComponent = Components.components.htmlelement;
  var TextAreaComponent = Components.components.textarea;
  var SelectComponent = Components.components.select;

  function stubOf(Base, typeName, badge) {
    class Stub extends Base {
      static schema() {
        return Base.schema.apply(Base, [{ type: typeName }].concat(Array.prototype.slice.call(arguments)));
      }
      get defaultSchema() { return Stub.schema(); }
      render() {
        var html = super.render.apply(this, arguments);
        return html.replace(/<label/, '<span class="idm-stub-badge" title="NetIQ custom component (placeholder rendering)">' + badge + '</span><label');
      }
    }
    Components.addComponent(typeName, Stub);
  }

  // A heading; the vendor builder stores the text in `content`.
  class TitleComponent extends HTMLComponent {
    static schema() {
      return HTMLComponent.schema.apply(HTMLComponent, [{ type: 'title', tag: 'h3', input: false }].concat(Array.prototype.slice.call(arguments)));
    }
    get defaultSchema() { return TitleComponent.schema(); }
    get content() {
      var c = this.component.content || this.component.label || '';
      return '<h3 class="idm-title">' + c + '</h3>';
    }
  }
  Components.addComponent('title', TitleComponent);

  // A read-only labelled value.
  class LabelElementComponent extends TextFieldComponent {
    static schema() {
      return TextFieldComponent.schema.apply(TextFieldComponent, [{ type: 'labelelement', disabled: true }].concat(Array.prototype.slice.call(arguments)));
    }
    get defaultSchema() { return LabelElementComponent.schema(); }
  }
  Components.addComponent('labelelement', LabelElementComponent);

  stubOf(TextFieldComponent, 'dn_display', 'DN');
  stubOf(SelectComponent, 'dynamic_entity', 'entity');
  stubOf(TextFieldComponent, 'permission_requestDN', 'permission');
  stubOf(TextFieldComponent, 'dataItemMappingTextField', 'mapping');
  stubOf(HTMLComponent, 'data_item_mapping', 'mapping');
  stubOf(TextAreaComponent, 'tree', 'tree');
})();
